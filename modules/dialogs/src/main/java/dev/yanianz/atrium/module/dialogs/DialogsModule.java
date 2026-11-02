package dev.yanianz.atrium.module.dialogs;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.module.dialogs.BedrockForm.Model;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.ButtonSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.InputSpec;
import dev.yanianz.atrium.module.dialogs.DialogDefinition.TextInput;
import io.papermc.paper.dialog.DialogResponseView;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Admin-defined dialogs built with the Paper dialog API. One file per dialog in
 * {@code plugins/Atrium/modules/dialogs/}. Button actions run through the {@link ActionService} (actions module).
 *
 * <p>Security model: dialog templates are trusted (admin-written); submitted values are not. Each submitted value
 * is validated on the server ({@link InputValidator}) and handed to the action service as a variable, which inserts
 * it literally (plain text in messages, rejected in commands when unsafe). Values never reach MiniMessage,
 * PlaceholderAPI or a dialog definition. Variables live only in the per-click map, never on the shared definition.
 *
 * <p>Bedrock players (Geyser/Floodgate) cannot render a Paper dialog, so they get the native form described by
 * {@link BedrockForm}. Both paths validate with the same {@link InputValidator} and run the follow-up on the
 * player's own thread.
 */
public final class DialogsModule implements AtriumModule {
    static final String PERM_OPEN = "atrium.dialog.open";
    static final String PERM_OPEN_OTHERS = "atrium.dialog.open.others";
    static final String PERM_LIST = "atrium.dialog.list";
    static final String PERM_RELOAD = "atrium.dialog.reload";
    static final String PERM_PREVIEW = "atrium.dialog.preview";
    static final String PERM_COOLDOWN_BYPASS = "atrium.dialog.cooldown.bypass";
    /** Cooldown key prefix; the dialog id follows. */
    private static final String COOLDOWN_PREFIX = "dialogs:";

    /** One shown dialog: its buttons share this, so only the first click of a showing counts. */
    record Session(UUID viewer, DialogDefinition definition, long generation, AtomicBoolean used) {}

    private ModuleContext ctx;
    private ConfigHandle<DialogsConfig> config;
    private DialogRenderer renderer;
    private volatile DialogLoader.Result loaded = new DialogLoader.Result(Map.of(), List.of(), List.of());
    private DialogValueStore values;
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean stopping;
    private volatile @Nullable FloodgateDialogs bedrock;
    private final AtomicBoolean warnedNoActions = new AtomicBoolean();
    private final AtomicBoolean warnedNoPlaceholders = new AtomicBoolean();
    private final AtomicBoolean warnedNoBedrock = new AtomicBoolean();

    @Override
    public String id() {
        return "dialogs";
    }

    @Override
    public String description() {
        return "Admin-defined dialogs with validated inputs and safe button actions";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("actions");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.stopping = false;
        this.config = context.config(DialogsConfig.class, DialogsConfig::new);
        this.renderer = new DialogRenderer(context.messages());

        context.permission(PERM_OPEN, PermissionDefault.TRUE, "Open dialogs (each dialog may require more)");
        context.permission(PERM_OPEN_OTHERS, PermissionDefault.OP, "Open a dialog for another player");
        context.permission(PERM_LIST, PermissionDefault.TRUE, "List the dialogs you can open");
        context.permission(PERM_RELOAD, PermissionDefault.OP, "Reload the dialog files");
        context.permission(PERM_PREVIEW, PermissionDefault.OP, "Preview a dialog without running its actions");
        context.permission(PERM_COOLDOWN_BYPASS, PermissionDefault.OP, "Ignore per-dialog cooldowns");

        ctx.migrate(DialogValueStore.MIGRATIONS);
        this.values = new DialogValueStore(ctx, config);
        createFolder();
        apply(load());
        this.bedrock = createBedrockHook();

        DialogCommands commands = new DialogCommands(this);
        commands.register();
        context.track(commands);
    }

    @Override
    public void reload() throws Exception {
        apply(load());
        this.bedrock = createBedrockHook();
    }

    @Override
    public void disable() {
        stopping = true;
        this.bedrock = null;
    }

    ModuleContext context() {
        return ctx;
    }

    Map<String, DialogDefinition> dialogs() {
        return loaded.dialogs();
    }

    Optional<DialogDefinition> dialog(String name) {
        return Optional.ofNullable(loaded.dialogs().get(name));
    }

    Path folder() {
        return ctx.plugin().getDataPath().resolve("modules").resolve("dialogs");
    }

    /** Reads every file. Blocking file I/O. */
    DialogLoader.Result load() throws IOException {
        DialogsConfig cfg = config.get();
        Pattern pattern;
        try {
            pattern = Pattern.compile(cfg.defaultTextPattern);
        } catch (PatternSyntaxException e) {
            ctx.logger().warn("dialogs.yml default-text-pattern is not a valid regular expression ({}); "
                    + "using [A-Za-z0-9_ -]{0,32}", e.getDescription());
            pattern = Pattern.compile("[A-Za-z0-9_ -]{0,32}");
        }
        DialogParser.Defaults defaults = new DialogParser.Defaults(pattern,
                Math.max(1, Math.min(DialogParser.MAX_TEXT_LENGTH, cfg.defaultTextMaxLength)));
        Function<List<String>, List<String>> check = ctx.services().get(ActionService.class)
                .<Function<List<String>, List<String>>>map(service -> service::validate)
                .orElse(null);
        return DialogLoader.load(folder(), defaults, check);
    }

    /** Installs a load result and logs its problems. Old dialogs still shown become outdated. */
    void apply(DialogLoader.Result result) {
        result.errors().forEach(line -> ctx.logger().warn("Dialog skipped: {}", line));
        result.warnings().forEach(line -> ctx.logger().warn("Dialog warning: {}", line));
        this.loaded = result;
        this.generation.incrementAndGet();
        ctx.logger().info("Loaded {} dialogs ({} files skipped)", result.dialogs().size(), result.failedFiles());
    }

    /** Asynchronous reload for the command: files are read on an I/O thread. */
    CompletableFuture<DialogLoader.Result> reloadAsync() {
        return ctx.scheduler().supplyIo(this::load).thenApply(result -> {
            apply(result);
            this.bedrock = createBedrockHook();
            return result;
        });
    }

    /**
     * Creates the Floodgate hook when the plugin is present and the config allows it. The class is only loaded
     * here, after the plugin check, so a server without Floodgate never resolves its types.
     */
    private @Nullable FloodgateDialogs createBedrockHook() {
        if (!config.get().bedrockForms) {
            return null;
        }
        if (!ctx.plugin().getServer().getPluginManager().isPluginEnabled("floodgate")) {
            return null;
        }
        try {
            return new FloodgateDialogs();
        } catch (LinkageError | RuntimeException e) {
            if (warnedNoBedrock.compareAndSet(false, true)) {
                ctx.logger().warn("Floodgate is enabled but its API cannot be used; Bedrock players get Paper "
                        + "dialogs they cannot see. Check the Floodgate version.", e);
            }
            return null;
        }
    }

    /** Opens a dialog entry point: applies the per-dialog cooldown and runs {@code on-open}. */
    boolean openEntry(Player viewer, DialogDefinition definition) {
        if (stopping || !viewer.isOnline()) {
            return false;
        }
        Duration cooldown = definition.cooldown();
        if (cooldown != null && !viewer.hasPermission(PERM_COOLDOWN_BYPASS)) {
            Duration left = ctx.cooldowns().tryAcquire(ctx.users().user(viewer),
                    COOLDOWN_PREFIX + definition.id(), cooldown);
            if (!left.isZero()) {
                ctx.messages().send(viewer, "dialogs.cooldown", Msg.duration("time", left));
                return false;
            }
        }
        if (!open(viewer, definition, Map.of())) {
            return false;
        }
        runOnOpen(viewer, definition);
        return true;
    }

    /**
     * Shows a dialog. Call on the viewer's thread.
     *
     * @param previous raw values submitted earlier (a re-show after a rejected submission)
     * @return false when the dialog could not be built or sent; the player was already told
     */
    boolean open(Player viewer, DialogDefinition definition, Map<String, String> previous) {
        if (stopping || !viewer.isOnline()) {
            return false;
        }
        if (!conditionsMet(viewer, definition.conditions())) {
            ctx.messages().send(viewer, "dialogs.condition-failed");
            return false;
        }
        // Remembered values from an earlier opening (or an earlier session); explicit values win.
        Map<String, String> starting = previous.isEmpty() ? values.remembered(viewer.getUniqueId(), definition.id())
                : previous;
        try {
            FloodgateDialogs hook = bedrock;
            if (hook != null && config.get().bedrockForms && hook.isBedrock(viewer)) {
                return showBedrock(viewer, definition, starting, hook);
            }
            return showPaper(viewer, definition, starting);
        } catch (RuntimeException | LinkageError e) {
            buildFailed(viewer, definition, e);
            return false;
        }
    }

    /** Shows the dialog as a preview: no cooldown, no {@code on-open}, inert buttons that list their actions. */
    boolean preview(Player viewer, DialogDefinition definition) {
        return open(viewer, DialogPreview.apply(definition), Map.of());
    }

    private boolean showPaper(Player viewer, DialogDefinition definition, Map<String, String> previous) {
        DialogsConfig cfg = config.get();
        Session session = new Session(viewer.getUniqueId(), definition, generation.get(), new AtomicBoolean());
        ClickCallback.Options options = ClickCallback.Options.builder()
                .uses(1)
                .lifetime(Duration.ofMinutes(Math.max(1, Math.min(1440, cfg.callbackLifetimeMinutes))))
                .build();
        viewer.showDialog(renderer.render(DialogRenderer.visible(definition, viewer, conditions -> conditionsMetFor(viewer, conditions)), viewer,
                previous, (button, response, audience) -> clicked(session, button, response, audience), options,
                conditions -> conditionsMetFor(viewer, conditions)));
        return true;
    }

    private boolean showBedrock(Player viewer, DialogDefinition definition, Map<String, String> previous,
            FloodgateDialogs hook) {
        Model model = BedrockForm.build(DialogRenderer.visible(definition, viewer, conditions -> conditionsMetFor(viewer, conditions)), previous,
                permission -> DialogRenderer.allowed(viewer, permission),
                template -> plain(viewer, template));
        Session session = new Session(viewer.getUniqueId(), definition, generation.get(), new AtomicBoolean());
        boolean sent = hook.show(viewer, model, actionLabel(viewer),
                (answered, clickedButton, values) -> ctx.scheduler().entity(viewer,
                        () -> handleBedrock(viewer, session, answered, clickedButton, values), null));
        if (!sent) {
            ctx.logger().warn("Floodgate refused the form for dialog '{}' (viewer {})", definition.id(),
                    viewer.getName());
            ctx.messages().send(viewer, "dialogs.failed");
        }
        return sent;
    }

    /** Whether one condition list passes for the viewer. */
    private boolean conditionsMetFor(Player viewer, List<Condition> conditions) {
        return conditionsMet(viewer, conditions);
    }

    private boolean conditionsMet(Player viewer, List<Condition> conditions) {
        if (conditions.isEmpty()) {
            return true;
        }
        for (Condition condition : conditions) {
            if (!condition.test(viewer::hasPermission, token -> placeholder(viewer, token))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves a condition's left side. A {@code %token%} goes to PlaceholderAPI when it is available and the
     * config allows it; everything else is one of the built-in values. Unknown names return an empty string,
     * which makes the condition false.
     */
    private String placeholder(Player viewer, String token) {
        String name = token == null ? "" : token.strip();
        if (name.startsWith("%") && name.endsWith("%") && name.length() > 2) {
            String key = name.substring(1, name.length() - 1).strip();
            if (!config.get().placeholderConditions) {
                return "";
            }
            if (!ctx.plugin().getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
                if (warnedNoPlaceholders.compareAndSet(false, true)) {
                    ctx.logger().warn("Dialog conditions use %{}% but PlaceholderAPI is not enabled; "
                            + "those conditions stay false", key);
                }
                return "";
            }
            try {
                return DialogsPlaceholderHook.resolve(viewer, key);
            } catch (LinkageError | RuntimeException e) {
                if (warnedNoPlaceholders.compareAndSet(false, true)) {
                    ctx.logger().warn("PlaceholderAPI could not resolve %{}% for dialog conditions", key, e);
                }
                return "";
            }
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "player", "name" -> viewer.getName();
            case "uuid" -> viewer.getUniqueId().toString();
            case "world" -> viewer.getWorld().getName();
            case "online" -> Integer.toString(ctx.plugin().getServer().getOnlinePlayers().size());
            case "world_players" -> Integer.toString(viewer.getWorld().getPlayers().size());
            case "gamemode" -> viewer.getGameMode().name().toLowerCase(java.util.Locale.ROOT);
            case "level" -> Integer.toString(viewer.getLevel());
            case "health" -> Double.toString(viewer.getHealth());
            case "food" -> Integer.toString(viewer.getFoodLevel());
            case "x" -> Integer.toString(viewer.getLocation().getBlockX());
            case "y" -> Integer.toString(viewer.getLocation().getBlockY());
            case "z" -> Integer.toString(viewer.getLocation().getBlockZ());
            default -> {
                // A permission node can be tested directly as a value: permission:atrium.dialog.vip.
                if (name.toLowerCase(java.util.Locale.ROOT).startsWith("permission:")) {
                    yield Boolean.toString(viewer.hasPermission(name.substring(name.indexOf(':') + 1)));
                }
                yield "";
            }
        };
    }

    /** Runs the {@code on-open} lines once, after the dialog was actually shown. */
    private void runOnOpen(Player viewer, DialogDefinition definition) {
        if (definition.onOpen().isEmpty()) {
            return;
        }
        Optional<ActionService> actions = ctx.services().get(ActionService.class);
        if (actions.isEmpty()) {
            if (warnedNoActions.compareAndSet(false, true)) {
                ctx.logger().warn("Dialog '{}' has on-open actions but the actions module is not enabled",
                        definition.id());
            }
            return;
        }
        try {
            actions.get().run(definition.onOpen(), viewer, Map.of()).whenComplete((outcome, error) -> {
                if (error != null) {
                    ctx.logger().error("On-open actions of dialog '{}' for {} failed", definition.id(),
                            viewer.getName(), error);
                } else if (outcome.status() == ActionResult.Status.INVALID
                        || outcome.status() == ActionResult.Status.FAILED
                        || outcome.status() == ActionResult.Status.UNSAFE_VARIABLE) {
                    ctx.logger().warn("On-open actions of dialog '{}' for {} ended with {}: {}", definition.id(),
                            viewer.getName(), outcome.status(), outcome.detail());
                }
            });
        } catch (RuntimeException e) {
            ctx.logger().error("Running the on-open actions of dialog '{}' for {} failed", definition.id(),
                    viewer.getName(), e);
        }
    }

    /** Paper delivers clicks on its own thread: read the response here, act on the player's thread. */
    private void clicked(Session session, ButtonSpec button, DialogResponseView response, Audience audience) {
        if (stopping) {
            return;
        }
        if (!(audience instanceof Player player) || !player.getUniqueId().equals(session.viewer())) {
            ctx.logger().debug("Ignoring a dialog click from an unexpected audience for '{}'",
                    session.definition().id());
            return;
        }
        InputValidator.ResponseValues values = new PaperResponse(response);
        InputValidator.Result result = InputValidator.validate(session.definition().inputs(), values);
        Map<String, String> raw = rawValues(session.definition().inputs(), values);
        ctx.scheduler().entity(player, () -> handle(player, session, button, result, raw), null);
    }

    /** Bedrock answers arrive on Geyser's thread; {@link #open} already moved this onto the player's thread. */
    private void handleBedrock(Player player, Session session, Model model, int clickedButton,
            Map<Integer, Object> submitted) {
        if (stopping || !player.isOnline()) {
            return;
        }
        ButtonSpec button;
        if (model.custom()) {
            button = BedrockResponses.action(model, submitted);
        } else {
            button = clickedButton >= 0 && clickedButton < model.buttons().size()
                    ? model.buttons().get(clickedButton)
                    : null;
        }
        if (button == null) {
            ctx.messages().send(player, "dialogs.invalid-action");
            return;
        }
        InputValidator.Result result = InputValidator.validate(session.definition().inputs(),
                BedrockResponses.of(model, submitted));
        Map<String, String> raw = BedrockResponses.raw(model, submitted);
        handle(player, session, button, result, raw);
    }

    private void handle(Player player, Session session, ButtonSpec button, InputValidator.Result result,
            Map<String, String> raw) {
        if (stopping || !session.used().compareAndSet(false, true)) {
            return;
        }
        DialogDefinition definition = session.definition();
        if (session.generation() != generation.get()) {
            ctx.messages().send(player, "dialogs.outdated");
            return;
        }
        if (!DialogRenderer.allowed(player, button.permission())) {
            ctx.messages().send(player, "dialogs.no-permission-button");
            return;
        }
        // A condition may have changed while the dialog was open; re-check it before anything runs.
        if (!conditionsMetFor(player, button.conditions())) {
            ctx.messages().send(player, "dialogs.condition-failed");
            return;
        }
        if (result instanceof InputValidator.Rejected rejected) {
            ctx.messages().send(player, "dialogs.invalid-" + reasonKey(rejected.reason()),
                    Msg.component("input", renderer.text(rejected.input().label(), player)),
                    Msg.number("max", rejected.input() instanceof TextInput text ? text.maxLength() : 0));
            if (config.get().reopenOnInvalidInput) {
                open(player, definition, raw);
            }
            return;
        }
        Map<String, String> variables = ((InputValidator.Accepted) result).variables();
        // Remember the raw submission (a bool stays "true"/"false"), which is what the renderer can pre-fill.
        values.remember(player.getUniqueId(), definition.id(), raw);
        if (button.actions().isEmpty()) {
            openNext(player, button);
            return;
        }
        Optional<ActionService> actions = ctx.services().get(ActionService.class);
        if (actions.isEmpty()) {
            if (warnedNoActions.compareAndSet(false, true)) {
                ctx.logger().warn("Dialog '{}' has button actions but the actions module is not enabled",
                        definition.id());
            }
            ctx.messages().send(player, "dialogs.actions-unavailable");
            return;
        }
        CompletableFuture<ActionResult> run;
        try {
            run = actions.get().run(button.actions(), player, variables);
        } catch (RuntimeException e) {
            ctx.logger().error("Running the actions of dialog '{}' for {} failed", definition.id(),
                    player.getName(), e);
            ctx.messages().send(player, "dialogs.failed");
            return;
        }
        run.whenComplete((outcome, error) -> {
            if (stopping) {
                return;
            }
            if (error != null) {
                ctx.logger().error("Actions of dialog '{}' for {} failed", definition.id(), player.getName(), error);
                reply(player, "failed");
                return;
            }
            switch (outcome.status()) {
                case COMPLETED -> ctx.scheduler().entity(player, () -> openNext(player, button), null);
                case UNSAFE_VARIABLE -> reply(player, "input-unsafe");
                case INVALID, FAILED -> {
                    ctx.logger().warn("Actions of dialog '{}' for {} ended with {}: {}", definition.id(),
                            player.getName(), outcome.status(), outcome.detail());
                    reply(player, "failed");
                }
                case LIMIT_REACHED -> reply(player, "busy");
                default -> {
                    // STOPPED, CONDITION_FAILED, INSUFFICIENT_FUNDS, PLAYER_GONE: the action lines themselves
                    // decide what the player is told.
                }
            }
        });
    }

    private void openNext(Player player, ButtonSpec button) {
        if (button.openDialog() == null || stopping || !player.isOnline()) {
            return;
        }
        Optional<DialogDefinition> next = dialog(button.openDialog());
        if (next.isEmpty()) {
            ctx.logger().warn("A dialog button opens unknown dialog '{}'", button.openDialog());
            ctx.messages().send(player, "dialogs.unknown", Msg.unparsed("name", button.openDialog()));
            return;
        }
        if (!DialogRenderer.allowed(player, next.get().permission())) {
            ctx.messages().send(player, "dialogs.no-permission-dialog");
            return;
        }
        openEntry(player, next.get());
    }

    private void reply(Player player, String key) {
        ctx.scheduler().entity(player, () -> {
            if (!stopping) {
                ctx.messages().send(player, "dialogs." + key);
            }
        }, null);
    }

    private void buildFailed(Player viewer, DialogDefinition definition, Throwable error) {
        ctx.logger().error("Building dialog '{}' for {} failed", definition.id(), viewer.getName(), error);
        ctx.messages().send(viewer, "dialogs.build-failed", Msg.unparsed("name", definition.id()));
    }

    private static String reasonKey(InputValidator.Reason reason) {
        return switch (reason) {
            case MISSING -> "missing";
            case TOO_LONG -> "too-long";
            case PATTERN -> "pattern";
            case UNKNOWN_OPTION -> "option";
            case OUT_OF_RANGE -> "range";
        };
    }

    /** The submitted values as text, only to pre-fill the dialog again after a rejection. */
    private static Map<String, String> rawValues(List<InputSpec> inputs, InputValidator.ResponseValues values) {
        Map<String, String> raw = new HashMap<>();
        for (InputSpec input : inputs) {
            Object value = switch (input) {
                case DialogDefinition.BoolInput ignored -> values.bool(input.key());
                case DialogDefinition.NumberInput ignored -> values.number(input.key());
                default -> values.text(input.key());
            };
            if (value != null) {
                raw.put(input.key(), String.valueOf(value));
            }
        }
        return raw;
    }

    /** Trusted template to plain text, for forms that do not render MiniMessage. */
    private String plain(Player viewer, String template) {
        return PlainTextComponentSerializer.plainText().serialize(renderer.text(template, viewer));
    }

    private String actionLabel(Player viewer) {
        Component label = ctx.messages().render(viewer, "dialogs.bedrock-action");
        String text = PlainTextComponentSerializer.plainText().serialize(label);
        return text.isBlank() ? "Action" : text;
    }

    private void createFolder() throws IOException {
        Path folder = folder();
        if (Files.isDirectory(folder)) {
            return;
        }
        Files.createDirectories(folder);
        if (!config.get().createExample) {
            return;
        }
        try (InputStream in = DialogsModule.class.getResourceAsStream("/dialogs/example.yml")) {
            if (in == null) {
                ctx.logger().warn("Bundled dialogs/example.yml is missing; no example written");
                return;
            }
            Files.copy(in, folder.resolve("example.yml"));
        }
    }

    /** Adapter over Paper's response view. A value of the wrong type reads as missing. */
    private record PaperResponse(DialogResponseView view) implements InputValidator.ResponseValues {
        @Override
        public String text(String key) {
            return view.getText(key);
        }

        @Override
        public Boolean bool(String key) {
            return view.getBoolean(key);
        }

        @Override
        public Float number(String key) {
            return view.getFloat(key);
        }
    }
}
