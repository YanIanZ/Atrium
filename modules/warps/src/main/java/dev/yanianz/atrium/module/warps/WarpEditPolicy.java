package dev.yanianz.atrium.module.warps;

/**
 * Who may change a warp. Staff with the action's admin permission may change any warp; a player with
 * {@code atrium.warps.create} may change only the warps they created. The {@code /editwarp} editor applies the
 * same rule as the stand-alone commands, so {@code atrium.editwarp} alone never grants a change.
 */
final class WarpEditPolicy {
    /** An editor action and the admin permission of the command it mirrors. */
    enum Action {
        MOVE(WarpsModule.PERM_SETWARP),
        RENAME(WarpsModule.PERM_SETWARP),
        CATEGORY(WarpsModule.PERM_SETWARP),
        PERMISSION(WarpsModule.PERM_SETWARP),
        ICON(WarpsModule.PERM_SETWARP),
        SETTINGS(WarpsModule.PERM_SETWARP),
        LOCATIONS(WarpsModule.PERM_SETWARP),
        DELETE(WarpsModule.PERM_DELWARP);

        private final String adminPermission;

        Action(String adminPermission) {
            this.adminPermission = adminPermission;
        }

        String adminPermission() {
            return adminPermission;
        }
    }

    private WarpEditPolicy() {}

    /**
     * @param admin   the sender has the action's admin permission
     * @param creator the sender has {@code atrium.warps.create}
     * @param owner   the sender created the warp (false for server warps without a creator)
     */
    static boolean mayManage(boolean admin, boolean creator, boolean owner) {
        return admin || (creator && owner);
    }
}
