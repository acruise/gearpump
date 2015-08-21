/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.cluster.master

import akka.actor.{Actor, ActorRef, Props}
import akka.testkit.TestProbe
import io.gearpump.cluster.AppMasterToMaster.AppDataSaved
import io.gearpump.cluster.MasterToAppMaster.{AppMasterData, AppMastersData, AppMastersDataRequest, AppMasterRegistered}
import io.gearpump.cluster.MasterToClient.{SubmitApplicationResult, ResolveAppIdResult, ShutdownApplicationResult}
import io.gearpump.cluster.TestUtil
import io.gearpump.cluster.master.InMemoryKVService.{GetKV, PutKVSuccess, PutKV}
import io.gearpump.cluster.AppMasterToMaster._
import io.gearpump.cluster.ClientToMaster.{ResolveAppId, ShutdownApplication, SubmitApplication}
import io.gearpump.cluster.MasterToAppMaster._
import io.gearpump.cluster.MasterToClient.{ReplayApplicationResult, ResolveAppIdResult, ShutdownApplicationResult, SubmitApplicationResult}
import io.gearpump.cluster.MasterToClient.{SubmitApplicationResult, ShutdownApplicationResult, ReplayApplicationResult, ResolveAppIdResult}
import io.gearpump.cluster.master.InMemoryKVService.{PutKVSuccess, GetKVSuccess, GetKV, PutKV}
import io.gearpump.cluster.master.MasterHAService._
import io.gearpump.cluster._
import io.gearpump.cluster.appmaster.{AppMasterRuntimeInfo, ApplicationState}
import io.gearpump.cluster.master.InMemoryKVService.{GetKV, GetKVSuccess, PutKV, PutKVSuccess}
import io.gearpump.cluster.master.MasterHAService._
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.util.Success

class AppManagerSpec extends FlatSpec with Matchers with BeforeAndAfterEach with MasterHarness {
  var kvService: TestProbe = null
  var haService: TestProbe = null
  var appLauncher: TestProbe = null
  var appManager : ActorRef = null

  override def config = TestUtil.DEFAULT_CONFIG

  override def beforeEach() = {
    startActorSystem()
    kvService = TestProbe()(getActorSystem)
    haService = TestProbe()(getActorSystem)
    appLauncher = TestProbe()(getActorSystem)

    appManager = getActorSystem.actorOf(Props(new AppManager(haService.ref, kvService.ref, new DummyAppMasterLauncherFactory(appLauncher))))
    haService.expectMsg(GetMasterState)
    haService.reply(MasterState(0, (Set.empty[ApplicationState])))
  }

  override def afterEach() = {
    shutdownActorSystem()
  }

  "AppManager" should "handle appmaster message correctly" in {
    val appMaster = TestProbe()(getActorSystem)
    val worker = TestProbe()(getActorSystem)

    val register = RegisterAppMaster(appMaster.ref, AppMasterRuntimeInfo(0, "appName"))
    appMaster.send(appManager, register)
    appMaster.expectMsgType[AppMasterRegistered]
  }

  "DataStoreService" should "support Put and Get" in {
    val appMaster = TestProbe()(getActorSystem)
    appMaster.send(appManager, SaveAppData(0, "key", 1))
    kvService.expectMsgType[PutKV]
    kvService.reply(PutKVSuccess)
    appMaster.expectMsg(AppDataSaved)

    appMaster.send(appManager, GetAppData(0, "key"))
    kvService.expectMsgType[GetKV]
    kvService.reply(GetKVSuccess("key", 1))
    appMaster.expectMsg(GetAppDataResult("key", 1))
  }

  "AppManager" should "support application submission and shutdown" in {
    testClientSubmission(withRecover = false)
  }

  "AppManager" should "support application submission and recover if appmaster dies" in {
    Console.out.println("=================testing recover==============")
    testClientSubmission(withRecover = true)
  }

  "AppManager" should "handle client message correctly" in {
    val mockClient = TestProbe()(getActorSystem)
    mockClient.send(appManager, ShutdownApplication(1))
    assert(mockClient.receiveN(1).head.asInstanceOf[ShutdownApplicationResult].appId.isFailure)

    mockClient.send(appManager, ResolveAppId(1))
    assert(mockClient.receiveN(1).head.asInstanceOf[ResolveAppIdResult].appMaster.isFailure)

    mockClient.send(appManager, AppMasterDataRequest(1))
    mockClient.expectMsg(AppMasterData(AppMasterNonExist))
  }

  "AppManager" should "reject the application submission if the app name already existed" in {
    val app = TestUtil.dummyApp
    val submit = SubmitApplication(app, None, "username")
    val client = TestProbe()(getActorSystem)
    val appMaster = TestProbe()(getActorSystem)
    val worker = TestProbe()(getActorSystem)
    val appId = 1

    client.send(appManager, submit)

    haService.expectMsgType[UpdateMasterState]
    appLauncher.expectMsg(LauncherStarted(appId))
    appMaster.send(appManager, RegisterAppMaster(appMaster.ref, AppMasterRuntimeInfo(appId, app.name)))
    appMaster.expectMsgType[AppMasterRegistered]

    client.send(appManager, submit)
    assert(client.receiveN(1).head.asInstanceOf[SubmitApplicationResult].appId.isFailure)
  }

  def testClientSubmission(withRecover: Boolean) : Unit = {
    val app = TestUtil.dummyApp
    val submit = SubmitApplication(app, None, "username")
    val client = TestProbe()(getActorSystem)
    val appMaster = TestProbe()(getActorSystem)
    val worker = TestProbe()(getActorSystem)
    val appId = 1

    client.send(appManager, submit)

    haService.expectMsgType[UpdateMasterState]
    appLauncher.expectMsg(LauncherStarted(appId))
    appMaster.send(appManager, RegisterAppMaster(appMaster.ref, AppMasterRuntimeInfo(appId, app.name)))
    appMaster.expectMsgType[AppMasterRegistered]

    client.send(appManager, ResolveAppId(appId))
    client.expectMsg(ResolveAppIdResult(Success(appMaster.ref)))

    client.send(appManager, AppMastersDataRequest)
    client.expectMsgType[AppMastersData]

    client.send(appManager, AppMasterDataRequest(appId, false))
    client.expectMsgType[AppMasterData]

    if (!withRecover) {
      client.send(appManager, ShutdownApplication(appId))
      client.expectMsg(ShutdownApplicationResult(Success(appId)))
    } else {
      //do recover
      getActorSystem.stop(appMaster.ref)
      haService.expectMsg(GetMasterState)
      val appState =  ApplicationState(appId, "application1", 1, app , None, "username", null)
      haService.reply(MasterState(appId, (Set(appState))))
      appLauncher.expectMsg(LauncherStarted(appId))
    }
  }
}

class DummyAppMasterLauncherFactory(test: TestProbe) extends AppMasterLauncherFactory {

  override def props(appId: Int, executorId: Int, app: AppDescription, jar: Option[AppJar], username: String, master: ActorRef, client: Option[ActorRef]): Props = {
    Props(new DummyAppMasterLauncher(test, appId))
  }
}

class DummyAppMasterLauncher(test: TestProbe, appId: Int) extends Actor {

  test.ref ! LauncherStarted(appId)
  override def receive: Receive = {
    case any : Any => test.ref forward any
  }
}

case class LauncherStarted(appId : Int)