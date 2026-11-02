package dev.yanianz.atrium.module.dialogs;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * plugins/Atrium/modules/dialogs.yml. The dialogs themselves live in plugins/Atrium/modules/dialogs/*.yml.
 *
 * <p>Per-dialog settings ({@code cooldown}, {@code on-open}, {@code extends}, ...) are documented in the bundled
 * {@code example.yml} and are read from the dialog files, not from here.
 */
@ConfigSerializable
public class DialogsConfig {
    @Comment("Full-match regular expression a text input must satisfy when the input does not declare its own "
            + "'pattern'. Submitted text that does not match is rejected before any action runs. Keep it strict: "
            + "values may end up in commands.")
    public String defaultTextPattern = "[A-Za-z0-9_ -]{0,32}";

    @Comment("Maximum length of a text input that does not declare 'max-length' (clamped to 1-1024). "
            + "Enforced on the server too; the client limit alone is not trusted.")
    public int defaultTextMaxLength = 32;

    @Comment("Minutes a shown dialog stays clickable. After that its buttons do nothing (clamped to 1-1440).")
    public int callbackLifetimeMinutes = 10;

    @Comment("Show the dialog again (keeping the submitted values) when a submitted value is rejected.")
    public boolean reopenOnInvalidInput = true;

    @Comment("Write example.yml into the dialogs folder when the folder is created for the first time.")
    public boolean createExample = true;

    @Comment("Remember the values a player typed into each dialog and show them again when the dialog is\n"
            + "reopened or after they log in again. Only values that passed validation are stored.")
    public boolean rememberValues = true;

    @Comment("How many dialogs per player keep remembered values; the least recently changed are dropped (1-64).")
    public int savedValuesMaxDialogs = 32;

    @Comment("Allow %placeholder% conditions on dialogs and buttons (PlaceholderAPI must be enabled).\n"
            + "Built-in names (online, world_players, level, health, food, gamemode, x, y, z) work without it.")
    public boolean placeholderConditions = true;

    @Comment("Show dialogs to Bedrock players (Geyser/Floodgate) as native Bedrock forms instead of Paper dialogs, "
            + "which a Bedrock client cannot render. Their answers are validated exactly like Java answers. "
            + "Requires the floodgate plugin; without it this setting does nothing. A custom form has one submit "
            + "button, so a dialog with several buttons gets a dropdown that picks the button.")
    public boolean bedrockForms = true;
}
