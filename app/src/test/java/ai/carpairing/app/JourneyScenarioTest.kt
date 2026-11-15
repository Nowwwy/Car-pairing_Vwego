package ai.carpairing.app

import org.junit.Assert.*
import org.junit.Test

/** State-machine checks only; these do not call a vehicle SDK or prove audio playback. */
class JourneyScenarioTest {
    private fun offered(kind: MessageKind): Journey = Journey(null).also {
        it.receive(CompanionMessage("일행", kind), 1_000)
        it.expireMessage(6_000)
        assertEquals(Journey.Phase.OFFER, it.phase)
    }

    @Test fun parkingRequiresSearchConsentThenSelectedPlaceConfirmation() {
        val journey = offered(MessageKind.PARKING)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        assertNull(journey.respond(true, 6_100))
        assertEquals(Journey.Phase.CONFIRM_PARKING, journey.phase)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        assertEquals(MessageKind.PARKING_APPLIED, journey.respond(true, 6_200, native = true))
        assertEquals(DemoRoute.PARKING, journey.route)
        assertEquals(Journey.Phase.IDLE, journey.phase)
    }

    @Test fun decliningParkingAtEitherChoiceKeepsExistingRoute() {
        for (afterSearch in listOf(false, true)) {
            val journey = offered(MessageKind.PARKING)
            if (afterSearch) journey.respond(true, 6_100)
            assertNull(journey.respond(false, 6_200))
            assertEquals(DemoRoute.ORIGINAL, journey.route)
            assertEquals(Journey.Phase.IDLE, journey.phase)
        }
    }

    @Test fun restOfferDoesNotChangeRouteUntilExplicitConfirmation() {
        val journey = offered(MessageKind.REST)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        journey.expireMessage(100_000)
        assertEquals(Journey.Phase.OFFER, journey.phase)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        assertEquals(MessageKind.REST_APPLIED, journey.respond(true, 100_100, native = true))
        assertEquals(DemoRoute.REST, journey.route)
    }

    @Test fun decliningRestOrNewDestinationNeverProducesAppliedEvent() {
        for (kind in listOf(MessageKind.REST, MessageKind.DESTINATION)) {
            val journey = offered(kind)
            assertNull(journey.respond(false, 6_100))
            assertEquals(DemoRoute.ORIGINAL, journey.route)
            assertEquals(Journey.Phase.IDLE, journey.phase)
            assertNull(journey.respond(true, 6_200, native = true))
        }
    }

    @Test fun acceptedDestinationHasItsOwnConfirmationEvent() {
        val journey = offered(MessageKind.DESTINATION)
        assertEquals(MessageKind.DESTINATION_APPLIED, journey.respond(true, 6_100, native = true))
        assertEquals(Journey.Phase.IDLE, journey.phase)
        assertNull(journey.respond(true, 6_200, native = true))
    }

    @Test fun delayedMessageKeepsSenderAmountAndUncertainty() {
        val journey = Journey(null)
        journey.receive(CompanionMessage("민수", MessageKind.DELAY, "20분 정도 늦을 것 같습니다."), 1_000)
        journey.expireMessage(6_000)
        assertEquals("민수 · 20분 정도 늦을 것 같습니다.", journey.delayedSender)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        assertEquals(Journey.Phase.IDLE, journey.phase)
    }

    @Test fun pendingOfferSurvivesNewMessagesAndDeclineAdvancesInbox() {
        val journey = offered(MessageKind.REST)
        val delay = CompanionMessage("민수", MessageKind.DELAY, "10분 늦을 것 같아", id = 2)
        journey.receive(delay, 7_000)
        assertEquals(MessageKind.REST, journey.message?.kind)
        assertEquals(Journey.Phase.OFFER, journey.phase)
        assertNull(journey.respond(false, 8_000))
        assertEquals(delay, journey.message)
        assertEquals(Journey.Phase.MESSAGE, journey.phase)
        assertEquals(DemoRoute.ORIGINAL, journey.route)
    }
    @Test fun changedDestinationOffersAcceptAndAckNeverCreatesAnotherOffer() {
        val kind = MessageKind.fromWire("destination_changed")
        assertNotNull("Confirmed personal change must be understood", kind)
        val journey = offered(kind!!)
        assertEquals(MessageKind.DESTINATION_APPLIED, journey.respond(true, 6_100, native = true))
        val sender = Journey(null)
        sender.receive(CompanionMessage("뒤차", MessageKind.DESTINATION_APPLIED), 7_000)
        sender.expireMessage(12_000)
        assertEquals(Journey.Phase.IDLE, sender.phase)
    }

    @Test fun declinedChangedDestinationKeepsCurrentRoute() {
        val kind = MessageKind.fromWire("destination_changed")
        assertNotNull(kind)
        val journey = offered(kind!!)
        assertNull(journey.respond(false, 6_100))
        assertEquals(DemoRoute.ORIGINAL, journey.route)
        assertEquals(Journey.Phase.IDLE, journey.phase)
    }
}
