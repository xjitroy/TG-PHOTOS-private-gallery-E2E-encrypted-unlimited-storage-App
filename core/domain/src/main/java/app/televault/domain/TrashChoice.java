package app.televault.domain;

import java.util.List;

/** Presentation targets and cross-store safety; these scopes always mean recoverable Trash. */
public final class TrashChoice {
    public enum Scope { DEVICE, CLOUD, EVERYWHERE }
    private TrashChoice() {}
    public static List<Scope> options(int cloud) {
        return cloud > 0 ? List.of(Scope.DEVICE,Scope.CLOUD,Scope.EVERYWHERE) : List.of(Scope.DEVICE,Scope.EVERYWHERE);
    }
    public static boolean enabled(Scope scope,int device,int cloud) {
        return switch(scope) { case DEVICE -> device>0; case CLOUD -> cloud>0; case EVERYWHERE -> device>0 || cloud>0; };
    }
    public static boolean cloudAfterDevice(Scope scope,int planned,int completed) {
        return scope==Scope.CLOUD || scope==Scope.EVERYWHERE && planned>=0 && completed==planned;
    }
}
