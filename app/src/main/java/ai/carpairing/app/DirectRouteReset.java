package ai.carpairing.app;

/** Coordinates explicit route restoration from asynchronous SDK observations. */
final class DirectRouteReset {
    enum Action { WAIT, REMOVE_FIRST, REMOVE_SECOND, REQUEST_DESTINATION, DONE }
    private int lastRemovalCount = -1;
    private boolean requested, destinationMatches, emptyAfterRequest;
    Action waypoints(int count) {
        if (count < 0 || count > 2) throw new IllegalArgumentException("Invalid waypoint count");
        if (requested) {
            emptyAfterRequest = count == 0;
            return complete() ? Action.DONE : Action.WAIT;
        }
        if (count == 0) { requested = true; return Action.REQUEST_DESTINATION; }
        if (lastRemovalCount == count) return Action.WAIT;
        lastRemovalCount = count;
        return count == 2 ? Action.REMOVE_SECOND : Action.REMOVE_FIRST;
    }
    boolean destination(boolean matches) {
        if (requested) destinationMatches = matches;
        return complete();
    }
    private boolean complete() { return requested && destinationMatches && emptyAfterRequest; }
}
