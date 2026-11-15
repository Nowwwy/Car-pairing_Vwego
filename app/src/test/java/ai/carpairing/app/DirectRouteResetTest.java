package ai.carpairing.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DirectRouteResetTest {
    @Test public void clearsStopsFromLastToFirstWithoutSendingDuplicateDeletes() {
        DirectRouteReset reset = new DirectRouteReset();
        assertEquals(DirectRouteReset.Action.REMOVE_SECOND, reset.waypoints(2));
        assertEquals(DirectRouteReset.Action.WAIT, reset.waypoints(2));
        assertEquals(DirectRouteReset.Action.REMOVE_FIRST, reset.waypoints(1));
        assertEquals(DirectRouteReset.Action.WAIT, reset.waypoints(1));
        assertEquals(DirectRouteReset.Action.REQUEST_DESTINATION, reset.waypoints(0));
    }
    @Test public void matchingDestinationDoesNotHideTheOldRestStop() {
        DirectRouteReset reset = new DirectRouteReset();
        assertFalse(reset.destination(true));
        assertEquals(DirectRouteReset.Action.REMOVE_FIRST, reset.waypoints(1));
        assertFalse(reset.destination(true));
        assertEquals(DirectRouteReset.Action.REQUEST_DESTINATION, reset.waypoints(0));
        assertFalse(reset.destination(true));
        assertEquals(DirectRouteReset.Action.DONE, reset.waypoints(0));
    }
    @Test public void freshDestinationAndEmptyStopListAreBothRequiredAfterRequest() {
        DirectRouteReset reset = new DirectRouteReset();
        assertEquals(DirectRouteReset.Action.REQUEST_DESTINATION, reset.waypoints(0));
        assertEquals(DirectRouteReset.Action.WAIT, reset.waypoints(0));
        assertFalse(reset.destination(false));
        assertTrue(reset.destination(true));
    }
}
