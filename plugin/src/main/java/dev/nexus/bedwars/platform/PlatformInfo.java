package dev.nexus.bedwars.platform;

/** Immutable description of the server software we are running on. */
public record PlatformInfo(boolean folia, String implementation) {

    public static PlatformInfo detect() {
        boolean folia = classExists("io.papermc.paper.threadedregions.RegionizedServer");
        return new PlatformInfo(folia, folia ? "Folia" : "Paper");
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
