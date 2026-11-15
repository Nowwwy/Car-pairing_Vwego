package ai.carpairing.app

import org.junit.Assert.*
import org.junit.Test

class RoadEstimateRulesTest {
    private val origin=MapPoint(37.49721,127.02805)
    private val target=MapPoint(37.552,126.972)
    private val key=RoadRouteKey("123456","session-a","car-a",origin,target,emptyList())

    @Test fun sampleExpiresAtSixtySecondsEvenWhenPresenceStillUpdates(){
        assertEquals(120,RoadEstimateRules.remaining(120,1_000,60_999))
        assertNull(RoadEstimateRules.remaining(120,1_000,61_000))
    }
    @Test fun clockResetAndImpossibleTimesNeverProduceAnEta(){
        assertNull(RoadEstimateRules.remaining(120,2_000,1_000))
        assertNull(RoadEstimateRules.remaining(-1,1_000,1_000))
        assertNull(RoadEstimateRules.remaining(604_801,1_000,1_000))
        assertEquals(1,RoadEstimateRules.remaining(1,1_000,3_000))
    }
    @Test fun gpsNoiseReusesSampleButMovedVehicleInvalidatesIt(){
        assertTrue(key.canReuseFor(key.copy(origin=MapPoint(origin.latitude+0.00001,origin.longitude))))
        assertFalse(key.canReuseFor(key.copy(origin=MapPoint(origin.latitude+0.002,origin.longitude))))
        assertTrue(RoadEstimateRules.distanceMeters(origin,MapPoint(origin.latitude+0.001,origin.longitude))>100.0)
    }
    @Test fun anotherRoomSessionVehicleDestinationOrWaypointCannotReuseEstimate(){
        assertFalse(key.canReuseFor(key.copy(code="654321")))
        assertFalse(key.canReuseFor(key.copy(token="new-session")))
        assertFalse(key.canReuseFor(key.copy(vehicle="car-b")))
        assertFalse(key.canReuseFor(key.copy(target=origin)))
        assertFalse(key.canReuseFor(key.copy(stops=listOf(origin))))
    }
    @Test fun nearbyOriginsDoNotAccumulateDriftBeyondOriginalSample(){
        val fiftyMeters=MapPoint(origin.latitude+0.00045,origin.longitude)
        val hundredFiftyMeters=MapPoint(origin.latitude+0.00135,origin.longitude)
        assertTrue(key.canReuseFor(key.copy(origin=fiftyMeters)))
        assertFalse(key.canReuseFor(key.copy(origin=hundredFiftyMeters)))
    }
    @Test fun refreshStartsBeforeExpiryAndDoesNotRepeatAfterFreshReplacement(){
        assertFalse(RoadEstimateRules.shouldRefresh(1_000L,1_000L,45_999L))
        assertTrue(RoadEstimateRules.shouldRefresh(1_000L,1_000L,46_000L))
        assertFalse(RoadEstimateRules.shouldRefresh(1_000L,46_000L,47_000L))
        assertTrue(RoadEstimateRules.shouldRefresh(1_000L,46_000L,56_000L))
        assertFalse(RoadEstimateRules.shouldRefresh(46_000L,46_000L,56_000L))
    }
    @Test fun failedRefreshRetainsValidSampleWithoutRenewingItsExpiry(){
        val previous=RoadEstimate(listOf(origin,target),120,500.0,"road",1_000L)
        assertSame(previous,RoadEstimateRules.afterRefresh(previous,null,46_000L))
        assertSame(previous,RoadEstimateRules.afterRefresh(previous,null,60_999L))
        assertNull(RoadEstimateRules.afterRefresh(previous,null,61_000L))
        val replacement=previous.copy(observedAt=46_000L)
        assertSame(replacement,RoadEstimateRules.afterRefresh(previous,replacement,50_000L))
    }
}

class CompanionEtaTest {
    private fun peer(source:String,sample:Long?=120L)=RoomApi.Companion(
        id="car-b",name="B",position=null,lastSeen=1_000L,arrivalAt=121_000L,
        etaSource=source,etaObservedAt=1_000L,sampledRemaining=sample)
    @Test fun roadSampleHoldsDurationWhileNativeArrivalCountsDown(){
        assertEquals(120L,peer("road_estimate").remaining(11_000L))
        assertEquals(110L,peer("pleos").remaining(11_000L))
    }
    @Test fun freshHeartbeatDoesNotReviveAnExpiredRoadSample(){
        assertNull(peer("road_estimate").copy(lastSeen=61_000L).remaining(61_000L))
        assertNull(peer("road_estimate",null).remaining(1_000L))
        assertNull(peer("road_estimate").copy(etaObservedAt=null).remaining(1_000L))
    }
}

class WaypointReadinessTest {
    @Test fun initialDestinationAndWaypointQueriesMustBothComplete(){
        assertEquals(WaypointReadiness.WAITING,WaypointReadinessRules.evaluate(true,false,false,false,null,false))
        assertEquals(WaypointReadiness.WAITING,WaypointReadinessRules.evaluate(true,false,true,true,null,false))
        assertEquals(WaypointReadiness.READY,WaypointReadinessRules.evaluate(true,false,true,true,0,false))
    }
    @Test fun knownUnavailableRouteAndFullSlotsAreFinalFailures(){
        assertEquals(WaypointReadiness.NO_DESTINATION,WaypointReadinessRules.evaluate(true,false,true,false,0,false))
        assertEquals(WaypointReadiness.FULL,WaypointReadinessRules.evaluate(true,false,true,true,2,false))
        assertEquals(WaypointReadiness.READY,WaypointReadinessRules.evaluate(true,false,true,true,2,true))
        assertEquals(WaypointReadiness.WAITING,WaypointReadinessRules.evaluate(true,true,true,true,0,false))
    }
}
