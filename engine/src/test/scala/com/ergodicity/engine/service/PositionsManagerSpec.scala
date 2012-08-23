package com.ergodicity.engine.service

import akka.actor.{Terminated, ActorRef, Props, ActorSystem}
import org.scalatest.{GivenWhenThen, BeforeAndAfterAll, WordSpec}
import akka.event.Logging
import akka.testkit._
import akka.util.duration._
import org.mockito.Mockito._
import akka.actor.FSM.{Transition, SubscribeTransitionCallBack}
import com.ergodicity.engine.{Services, Strategies, Engine}
import com.ergodicity.engine.Components.{PosReplication, CreateListener}
import ru.micexrts.cgate.{Connection => CGConnection, Listener => CGListener, ISubscriber}
import com.ergodicity.cgate.config.Replication
import com.ergodicity.engine.service.Service.Start
import com.ergodicity.core.PositionsTrackingState
import com.ergodicity.engine.underlying.UnderlyingConnection

class PositionsManagerSpec extends TestKit(ActorSystem("PositionsManagerSpec", com.ergodicity.engine.EngineSystemConfig)) with ImplicitSender with WordSpec with BeforeAndAfterAll with GivenWhenThen {
  val log = Logging(system, self)

  override def afterAll() {
    system.shutdown()
  }

  private def mockEngine(serviceManager: TestProbe, positions: TestProbe) = TestActorRef(new {
    val ServiceManager = serviceManager.ref
    val StrategyEngine = system.deadLetters

    val Positions = positions.ref
  } with Engine with Services with Strategies with UnderlyingConnection with CreateListener with PosReplication with Portfolio {

    val underlyingConnection = mock(classOf[CGConnection])

    def PosStream = system.deadLetters

    def posReplication = mock(classOf[Replication])

    def listener(connection: CGConnection, config: String, subscriber: ISubscriber) = mock(classOf[CGListener])
  })

  "Portfolio Manager" must {
    "stash messages before ConnectionService is activated" in {
      val serviceManager = TestProbe()
      val positions = TestProbe()

      val engine = mockEngine(serviceManager, positions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new PositionsManager(engine)).withDispatcher("deque-dispatcher"), "PositionsManager")

      when("got Start message before connection service started")
      manager ! Start
      then("should stash it")
      positions.expectNoMsg(300.millis)

      when("Connection Service started")
      manager ! ServiceStarted(ConnectionServiceId)

      then("should track Portfolio state")
      positions.expectMsg(SubscribeTransitionCallBack(manager))

      when("Portfolio goes online")
      manager ! Transition(positions.ref, PositionsTrackingState.Binded, PositionsTrackingState.Online)

      then("Service Manager should be notified")
      serviceManager.expectMsg(ServiceStarted(PortfolioServiceId))
    }

    "stop actor on Service.Stop message" in {
      val serviceManager = TestProbe()
      val positions = TestProbe()

      val engine = mockEngine(serviceManager, positions).underlyingActor
      val manager: ActorRef = TestActorRef(Props(new PositionsManager(engine)).withDispatcher("deque-dispatcher"), "PositionsManager")

      manager ! ServiceStarted(ConnectionServiceId)
      watch(manager)

      when("stop Service")
      manager ! Service.Stop

      when("service manager should be notified")
      serviceManager.expectMsg(ServiceStopped(PortfolioServiceId))

      and("positions manager actor terminated")
      expectMsg(Terminated(manager))
    }
  }
}