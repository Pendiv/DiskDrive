package local.disc;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.event.player.PlayerItemGroupCooldownEvent;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.keys.tags.DamageTypeTagKeys;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

final class DiscMatch implements BasicCommand, Listener {
    private static final int COUNTDOWN_TICKS = 20 * 3;
    private static final int RESPAWN_TICKS = 20 * 3;
    private static final int DEATH_CREDIT_TICKS = 20 * 8;
    private static final int NO_THROW_TICKS = 20 * 20;
    private static final int NO_EXCHANGE_TICKS = 20 * 30;
    static final double SUDDEN_DEATH_DAMAGE = 1.5;
    private static final int STARTING_BREAD = 4;
    private static final int RECONSTRUCTOR_STARTING_FRAGMENTS = 3;
    private static final int RECONSTRUCTOR_RESUPPLY_TICKS = 60 * 20;
    private static final int RECONSTRUCTOR_RESUPPLY_FRAGMENTS = 2;
    static final double RECONSTRUCTOR_FRAGMENT_BONUS = 0.5;
    private static final double SNIPER_SNIPE_BONUS = 2.5;
    private static final String ADMIN_PERMISSION = "disc.match.admin";
    private static final String EDIT_PERMISSION = "disc.edit";
    private static final String MAP_SETTING = "match.map";
    private static final NamespacedKey SHIELD_COOLDOWN_GROUP = NamespacedKey.minecraft("shield");

    private final DiscPlugin plugin;
    private final Map<DiscArena.Layout, DiscArena> arenas = new EnumMap<>(DiscArena.Layout.class);
    private DiscArena arena;
    private final DiscLobby lobby;
    private final DiscItem discItem;
    private final ItemBoxes itemBoxes;
    private final MatchLoot loot;
    private final DiscMechanisms mechanisms;
    private final MatchBots bots;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID, MatchPlayerState> previews = new LinkedHashMap<>();
    private volatile Location lobbyEntry;
    private Round round;
    private boolean preparing;
    private long tick;

    DiscMatch(DiscPlugin plugin, DiscItem discItem, ItemBoxes itemBoxes, MatchBoxLayout boxLayout,
              DiscMechanisms mechanisms, MatchBots bots) {
        this.plugin = plugin;
        this.bots = bots;
        this.itemBoxes = itemBoxes;
        for (DiscArena.Layout layout : DiscArena.Layout.values()) {
            arenas.put(layout, new DiscArena(plugin.getServer(), layout));
        }
        String selected = plugin.getConfig().getString(MAP_SETTING, DiscArena.Layout.HALL.id());
        this.arena = arenas.values().stream().filter(candidate -> candidate.layout().id().equals(selected))
                .findFirst().orElse(arenas.get(DiscArena.Layout.HALL));
        this.lobby = new DiscLobby(plugin.getServer());
        this.discItem = discItem;
        this.mechanisms = mechanisms;
        this.loot = new MatchLoot(itemBoxes, boxLayout, discItem, this::canOpenMatchLoot);
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 2 && args[0].equalsIgnoreCase("map")) {
            selectMap(sender, args[1]);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role") && sender instanceof Player player) {
            chooseRole(player, args[1]);
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                sender.sendMessage("試合を管理する権限がありません。");
                return;
            }
            switch (args[1]) {
                case "2" -> start(sender, 2);
                case "4" -> start(sender, 4);
                case "8" -> start(sender, 8);
                default -> sender.sendMessage("/discmatch start <2|4|8>");
            }
            return;
        }
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (preparing && !action.equals("status") && !action.equals("map")) {
            sendPreparing(sender);
            return;
        }
        switch (action) {
            case "lobby" -> {
                if (sender instanceof Player player) {
                    enterLobby(player);
                }
            }
            case "join" -> {
                if (sender instanceof Player player) {
                    join(player);
                }
            }
            case "role" -> {
                if (sender instanceof Player player) {
                    showRoles(player);
                }
            }
            case "leave" -> {
                if (sender instanceof Player player && !plugin.leaveFieldEditor(player) && !leave(player, true)) {
                    if (MatchPlayerState.hasPending(player)) {
                        restorePending(player);
                    } else {
                        player.sendMessage("参加中のディスク試合や待機列がありません。");
                    }
                }
            }
            case "status" -> sender.sendMessage(round == null
                    ? "待機中 " + queue.size() + " / " + arena.capacity() + "人  |  " + arena.layout().displayName()
                    : scoreLine(round));
            case "map" -> sender.sendMessage("選択中のマップ  " + arena.layout().displayName()
                    + " (" + arena.layout().id() + "、最大" + arena.capacity() + "人)");
            case "preview", "start", "stop" -> administer(sender, action);
            default -> sendUsage(sender);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("map") && source.getSender().hasPermission(ADMIN_PERMISSION)) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return arenas.keySet().stream().map(DiscArena.Layout::id).filter(id -> id.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("start") && source.getSender().hasPermission(ADMIN_PERMISSION)) {
            return List.of("2", "4", "8").stream().filter(size -> size.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("role") && source.getSender() instanceof Player) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> choices = new ArrayList<>();
            for (MatchRole role : MatchRole.values()) {
                if (role.id().startsWith(prefix)) {
                    choices.add(role.id());
                }
            }
            return choices;
        }
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        List<String> choices = new ArrayList<>(List.of("lobby", "join", "leave", "status", "map", "role"));
        if (source.getSender().hasPermission(ADMIN_PERMISSION)) {
            choices.addAll(List.of("preview", "start", "stop"));
        }
        return choices.stream().filter(choice -> choice.startsWith(prefix)).toList();
    }

    private void selectMap(CommandSender sender, String id) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("試合を管理する権限がありません。");
            return;
        }
        DiscArena selected = findArena(id);
        if (selected == null) {
            sender.sendMessage(mapUsage());
            return;
        }
        if (preparing || round != null) {
            sender.sendMessage("試合の準備・進行が終わってからマップを変更してください。");
            return;
        }
        if (queue.size() > selected.capacity()) {
            sender.sendMessage("待機列が " + selected.capacity() + " 人以下になると、このマップを選べます。");
            return;
        }
        arena = selected;
        plugin.getConfig().set(MAP_SETTING, selected.layout().id());
        plugin.saveConfig();
        sender.sendMessage(selected.layout().displayName() + "を選択しました。最大 " + selected.capacity() + " 人。");
    }

    private void administer(CommandSender sender, String action) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("試合を管理する権限がありません。");
            return;
        }
        switch (action) {
            case "start" -> start(sender);
            case "stop" -> {
                if (round == null) {
                    sender.sendMessage("進行中のディスク試合がありません。");
                } else {
                    finish("試合を終了しました。");
                }
            }
            case "preview" -> {
                if (sender instanceof Player player) {
                    preview(player);
                }
            }
            default -> throw new IllegalArgumentException(action);
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("/discmatch lobby | join | leave | status | map | role");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("/discmatch preview | start | stop\n" + mapUsage());
        }
    }

    private void showRoles(Player player) {
        Entrant entry = entrant(player);
        if (entry != null) {
            player.sendMessage("試合中の役職は " + entry.role.displayName() + " です。");
            return;
        }
        if (!DiscLobby.isLobby(player.getWorld())) {
            player.sendMessage("役職はロビーで選べます。");
            return;
        }
        MatchRole selected = MatchRole.selected(player);
        player.sendMessage("次の試合の役職を選んでください。現在 " + selected.displayName());
        for (MatchRole role : MatchRole.values()) {
            player.sendMessage(Component.text(role.displayName(), role == selected ? NamedTextColor.GREEN : NamedTextColor.AQUA)
                    .clickEvent(ClickEvent.runCommand("/discmatch role " + role.id()))
                    .append(Component.text("  " + role.description(), NamedTextColor.GRAY)));
        }
    }

    private void chooseRole(Player player, String id) {
        if (entrant(player) != null || !DiscLobby.isLobby(player.getWorld())) {
            player.sendMessage("役職はロビーで試合開始前に選んでください。");
            return;
        }
        MatchRole role = MatchRole.byId(id);
        if (role == null) {
            showRoles(player);
            return;
        }
        role.select(player);
        player.sendMessage("次の試合の役職を " + role.displayName() + " にしました。");
    }

    private String mapUsage() {
        return "/discmatch map " + arenas.keySet().stream().map(DiscArena.Layout::id).collect(Collectors.joining(" | "));
    }

    private DiscArena findArena(String id) {
        String canonical = "dual".equalsIgnoreCase(id) ? "duel" : id;
        return arenas.values().stream().filter(candidate -> candidate.layout().id().equalsIgnoreCase(canonical))
                .findFirst().orElse(null);
    }

    private static void sendPreparing(CommandSender sender) {
        sender.sendMessage("ワールドを準備中です。準備が終わってから実行してください。");
    }

    private void enterLobby(Player player) {
        if (player.isDead()) {
            player.sendMessage("リスポーンしてからロビーに移動してください。");
            return;
        }

        preparing = true;
        try {
            Location destination = prepareLobby();
            lobbyEntry = destination;
            plugin.leaveFieldEditor(player);
            if (entrant(player) != null || previews.containsKey(player.getUniqueId())) {
                leave(player, true);
            }
            if (MatchPlayerState.hasPending(player)) {
                restorePending(player);
                if (MatchPlayerState.hasPending(player)) {
                    return;
                }
            }
            if (player.isOnline() && player.teleport(destination)) {
                player.sendMessage("ロビーに移動しました。参加看板を右クリックすると待機列に入れます。");
            } else if (player.isOnline()) {
                player.sendMessage("ロビーに移動できませんでした。");
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not open the Disc lobby", error);
            player.sendMessage("ロビーに移動できませんでした。");
        } finally {
            preparing = false;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLobbySign(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY) {
            return;
        }
        DiscLobby.Action action = lobby.signAction(event.getClickedBlock());
        if (action == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || event.getPlayer().getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Player player = event.getPlayer();
        if (preparing) {
            sendPreparing(player);
            return;
        }
        switch (action) {
            case JOIN -> join(player);
            case ROLE -> showRoles(player);
            case SUPPLY -> plugin.giveMissingDiscs(player);
            case START -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && DiscLobby.isLobby(player.getWorld())) {
                    administer(player, "start");
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrapResetSign(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.useInteractedBlock() == Event.Result.DENY || !TrapResetSigns.isResetSign(event.getClickedBlock())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (event.getHand() != EquipmentSlot.HAND || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        World world = event.getClickedBlock().getWorld();
        if (!DiscLobby.isLobby(world) && !player.hasPermission(EDIT_PERMISSION)) {
            player.sendMessage("試合場の仕掛けをリセットするには編集権限が必要です。");
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || player.getWorld() != world) {
                return;
            }
            try {
                boolean complete = mechanisms.resetTraps(world);
                player.sendMessage(complete ? "このエリアのギミックをリセットしました。"
                        : "復元待ちのギミックがあります。場所が空くと元に戻ります。");
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not reset Disc traps in " + world.getName(), error);
                player.sendMessage("ギミックを復元できませんでした。");
            }
        });
    }

    private void join(Player player) {
        UUID id = player.getUniqueId();
        if (plugin.isFieldEditing(player)) {
            player.sendMessage("/edit end で試合場の編集を終えてから参加してください。");
            return;
        }
        if (round != null || queue.size() >= arena.capacity() && !queue.contains(id)) {
            player.sendMessage(round != null ? "ディスク試合が進行中です。" : "ディスク試合の待機列が満員です。");
            return;
        }
        MatchPlayerState preview = previews.remove(id);
        if (preview != null) {
            restore(player, preview);
        }
        if (MatchPlayerState.hasPending(player)) {
            restorePending(player);
            if (MatchPlayerState.hasPending(player)) {
                return;
            }
        }
        queue.add(id);
        player.sendMessage("ディスク試合に参加予約しました。 " + queue.size() + " / " + arena.capacity() + "人");
    }

    private void start(CommandSender sender) {
        start(sender, 0);
    }

    private void start(CommandSender sender, int total) {
        if (preparing) {
            sendPreparing(sender);
            return;
        }
        if (round != null) {
            sender.sendMessage("ディスク試合が進行中です。");
            return;
        }
        DiscArena targetArena = switch (total) {
            case 2 -> arenas.get(DiscArena.Layout.DUEL);
            case 4 -> arenas.get(DiscArena.Layout.OUTDOOR);
            case 8 -> arenas.get(DiscArena.Layout.HALL);
            default -> arena;
        };
        if (plugin.hasFieldEditors(targetArena.layout().id())) {
            sender.sendMessage("試合場を編集中です。編集者が /edit end を実行すると開始できます。");
            return;
        }
        List<Player> players = queue.stream().map(plugin.getServer()::getPlayer)
                .filter(player -> player != null && player.isOnline() && !player.isDead()).toList();
        if (total == 0 && players.size() < 2) {
            sender.sendMessage("生存中の参加者が2人以上必要です。各自 /discmatch join で参加できます。");
            return;
        }
        if (total != 0 && players.size() > total) {
            sender.sendMessage("待機中のプレイヤーが " + total + " 人を超えています。");
            return;
        }

        Round next = new Round(targetArena);
        preparing = true;
        try {
            next.world = prepareArena(next.arena);
            if (total > players.size()) {
                next.botChunks = new MatchBots.ArenaChunks(itemBoxes, next.world);
            }
            for (int slot = 0; slot < players.size(); slot++) {
                Player player = players.get(slot);
                if (!player.isOnline() || player.isDead()) {
                    throw new IllegalStateException("Entrant left during setup: " + player.getName());
                }
                MatchPlayerState state = capture(player);
                Entrant entry = new Entrant(player, state, next.arena.armorColor(slot), MatchRole.selected(player));
                next.players.put(player.getUniqueId(), entry);
                if (!player.teleport(next.arena.spawn(slot))) {
                    throw new IllegalStateException("Entry teleport failed for " + player.getName());
                }
                equip(entry, false);
                player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscPlugin.RETURN_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscItem.OFF_HAND_WARDEN_GROUP, 0);
                player.setCooldown(DiscPlugin.OFF_HAND_COOLDOWN_GROUP, 0);
                player.setCooldown(DiscPlugin.FRAGMENT_COOLDOWN_GROUP, 0);
            }
            if (players.stream().anyMatch(player -> !player.isOnline())) {
                throw new IllegalStateException("An entrant disconnected during setup");
            }
            for (int slot = players.size(); slot < total; slot++) {
                MatchBots.Body body = bots.spawn(UUID.randomUUID(), "DiscBOT" + (slot + 1), next.arena.spawn(slot));
                Entrant entry = new Entrant(body.player, null, next.arena.armorColor(slot), MatchRole.randomForBot());
                entry.body = body;
                entry.brain = new BotBrain(plugin, loot, body, entry.role);
                next.players.put(body.player.getUniqueId(), entry);
                equip(entry, false);
            }
            next.hud = new MatchHud(plugin.getServer(), next.players.values().stream().filter(entry -> entry.body != null)
                    .map(entry -> new MatchHud.BotLabel(entry.player.getName(), entry.role)).toList());
            mechanisms.beginArenaRound(next.world);
        } catch (RuntimeException error) {
            next.players.values().forEach(entry -> finishEntrant(entry, null));
            closeBotChunks(next);
            plugin.getLogger().log(Level.WARNING, "Could not start the Disc match", error);
            sender.sendMessage("試合場への移動に失敗しました。");
            return;
        } finally {
            preparing = false;
        }

        queue.clear();
        int roundMinutes = switch (next.arena.layout()) {
            case DUEL -> 3;
            case OUTDOOR -> 4;
            case HALL -> 5;
        };
        next.startsAt = tick + COUNTDOWN_TICKS;
        next.endsAt = next.startsAt + roundMinutes * 60L * 20;
        next.lastThrowAt = next.startsAt;
        next.lastExchangeAt = next.startsAt;
        round = next;
        watchers(next).forEach(player -> player.showBossBar(next.board));
        String mode = next.arena.layout() == DiscArena.Layout.DUEL ? "ディスク1on1" : "ディスクFFA";
        announce(next, mode + "を開始します。" + roundMinutes + "分間で最も得点した人が勝利です。");
        updateBoard(next);
    }

    private void preview(Player player) {
        UUID id = player.getUniqueId();
        if (plugin.isFieldEditing(player)) {
            player.sendMessage("/edit end で試合場の編集を終えてください。");
            return;
        }
        if (previews.containsKey(id) || entrant(player) != null || player.isDead()) {
            player.sendMessage("観戦・参加中です。退出するには /discmatch leave を使ってください。");
            return;
        }
        MatchPlayerState before = null;
        preparing = true;
        try {
            prepareArena(arena);
            Location destination = arena.overlook();
            before = capture(player);
            if (!player.teleport(destination)) {
                throw new IllegalStateException("Preview teleport failed for " + player.getName());
            }
            player.setGameMode(GameMode.SPECTATOR);
            previews.put(id, before);
            queue.remove(id);
            if (round != null) {
                player.showBossBar(round.board);
                updateBoard(round);
            }
            player.sendMessage("試合場を観戦しています。/discmatch leave で元の場所へ戻ります。");
        } catch (RuntimeException error) {
            if (before != null) {
                restore(player, before);
            }
            plugin.getLogger().log(Level.WARNING, "Could not open the Disc arena preview", error);
            player.sendMessage("試合場に移動できませんでした。");
        } finally {
            preparing = false;
        }
    }

    private MatchPlayerState capture(Player player) {
        plugin.returnDiscs(player);
        MatchPlayerState state = MatchPlayerState.capture(player);
        state.save(player);
        return state;
    }

    List<String> fieldTargets() {
        List<String> targets = new ArrayList<>();
        targets.add("lobby");
        arenas.keySet().forEach(layout -> targets.add(layout.id()));
        return List.copyOf(targets);
    }

    String resolveFieldTarget(String requested) {
        if (requested == null) {
            return arena.layout().id();
        }
        if (requested.equalsIgnoreCase("lobby")) {
            return "lobby";
        }
        DiscArena target = findArena(requested);
        return target == null ? null : target.layout().id();
    }

    Location fieldForEdit(Player player, String canonicalTarget) {
        if (preparing) {
            sendPreparing(player);
            return null;
        }
        if (player.isDead()) {
            player.sendMessage("リスポーンしてから編集してください。");
            return null;
        }
        boolean editingLobby = "lobby".equals(canonicalTarget);
        DiscArena target = editingLobby ? null : findArena(canonicalTarget);
        if (!editingLobby && target == null) {
            player.sendMessage("編集先を指定してください。/edit field " + String.join(" | ", fieldTargets()));
            return null;
        }
        if (entrant(player) != null || round != null && round.arena == target) {
            player.sendMessage("試合終了後にこのエリアを編集できます。");
            return null;
        }
        if (previews.containsKey(player.getUniqueId())) {
            leave(player, true);
        }
        if (MatchPlayerState.hasPending(player)) {
            restorePending(player);
            if (MatchPlayerState.hasPending(player)) {
                return null;
            }
        }

        preparing = true;
        try {
            Location destination;
            if (editingLobby) {
                destination = prepareLobby();
                lobbyEntry = destination;
            } else {
                prepareArena(target);
                destination = target.overlook();
            }
            queue.remove(player.getUniqueId());
            return destination;
        } finally {
            preparing = false;
        }
    }

    private World prepareArena(DiscArena selected) {
        World world = selected.world();
        mechanisms.prepareArena(world);
        ensureTrapResetSign(world);
        return world;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        try {
            lobbyEntry = prepareLobby();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not pre-load the Disc lobby", error);
        }
    }

    private Location prepareLobby() {
        Location destination = lobby.spawn();
        World world = destination.getWorld();
        try {
            mechanisms.prepareLobby(world);
            ensureTrapResetSign(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not prepare Disc lobby trap recovery", error);
        }
        try {
            loot.showLobby(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not place loot-box examples in the Disc lobby", error);
        }
        return destination;
    }

    private void ensureTrapResetSign(World world) {
        try {
            TrapResetSigns.ensure(world);
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not place the trap reset sign in " + world.getName(), error);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        Location destination = lobbyEntry;
        if (destination != null) {
            event.setSpawnLocation(destination.clone());
        }
    }

    private void equip(Entrant entry, boolean respawning) {
        Player player = entry.player;
        player.setGameMode(GameMode.ADVENTURE);
        PlayerInventory inventory = player.getInventory();
        inventory.setContents(new ItemStack[inventory.getSize()]);
        inventory.setHelmet(leatherArmor(Material.LEATHER_HELMET, entry.armorColor));
        inventory.setChestplate(leatherArmor(Material.LEATHER_CHESTPLATE, entry.armorColor));
        inventory.setLeggings(leatherArmor(Material.LEATHER_LEGGINGS, entry.armorColor));
        inventory.setBoots(leatherArmor(Material.LEATHER_BOOTS, entry.armorColor));
        player.clearActivePotionEffects();
        player.setAbsorptionAmount(0);
        player.setHealth(Math.min(20, player.getAttribute(Attribute.MAX_HEALTH).getValue()));
        player.setFoodLevel(20);
        player.setSaturation(5);
        player.setExhaustion(0);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setNoDamageTicks(0);
        if (entry.role == MatchRole.SHIFTER) {
            inventory.setItemInOffHand(DiscShields.create());
        }
        if (respawning) {
            restoreMatchDiscs(entry);
        } else {
            for (int index = 0; index < entry.role.starterDiscCount(); index++) {
                inventory.setItem(starterDiscSlot(entry.role, index), discItem.create(entry.role.starterDisc()));
            }
            inventory.setItem(1, new ItemStack(Material.BREAD, STARTING_BREAD));
            if (entry.role == MatchRole.RECONSTRUCTOR) {
                inventory.setItem(2, discItem.create(DiscType.FRAGMENT).asQuantity(RECONSTRUCTOR_STARTING_FRAGMENTS));
            }
            inventory.setHeldItemSlot(0);
        }
    }

    private void restoreMatchDiscs(Entrant entry) {
        Player player = entry.player;
        PlayerInventory inventory = player.getInventory();
        DiscType starter = entry.role.starterDisc();
        int starters = 0;
        for (RetainedDisc disc : entry.discsAtDeath) {
            plugin.restoreItem(player, disc.slot(), disc.item());
            if (discItem.type(disc.item()) == starter) {
                starters += disc.item().getAmount();
            }
        }
        entry.discsAtDeath = List.of();

        if (entry.body != null) {
            int wardenTicks = (int) Math.max(0, entry.wardenReadyAt - tick);
            player.setCooldown(DiscItem.WARDEN_COOLDOWN_GROUP, wardenTicks);
        }
        for (RetainedFlight flight : entry.flightsAtDeath) {
            plugin.restoreItem(player, flight.slot(), flight.item());
            DiscType type = discItem.type(flight.item());
            if (type == starter) {
                starters += flight.item().getAmount();
            }
            plugin.startReturnCooldown(player, flight.launchedType(), flight.slot());
        }
        entry.flightsAtDeath.clear();
        for (int index = starters; index < entry.role.starterDiscCount(); index++) {
            plugin.restoreItem(player, starterDiscSlot(entry.role, index), discItem.create(starter));
        }
        plugin.restoreItem(player, 1, new ItemStack(Material.BREAD, STARTING_BREAD));
        int previous = entry.selectedSlotAtDeath;
        inventory.setHeldItemSlot(discItem.matches(inventory.getItem(previous)) ? previous : 0);
    }

    private static int starterDiscSlot(MatchRole role, int index) {
        return index == 0 ? 0 : role == MatchRole.MORE_THROWER ? DiscItem.OFF_HAND_SLOT : 2;
    }

    boolean keepFlightForRespawn(DiscThrow flight) {
        Entrant entry = entrant(flight.owner());
        if (entry == null || flight.owner().getWorld() != round.world) {
            return false;
        }
        entry.flightsAtDeath.add(new RetainedFlight(flight.slot(), flight.item(), flight.type()));
        return true;
    }

    private void saveMatchDiscs(Entrant entry) {
        Player player = entry.player;
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        boolean loseFragments = entry.role == MatchRole.RECONSTRUCTOR;
        List<RetainedDisc> discs = new ArrayList<>();
        for (int slot = 0; slot < contents.length; slot++) {
            DiscType type = discItem.type(contents[slot]);
            if (loseFragments && type == DiscType.FRAGMENT) {
                inventory.setItem(slot, null);
            } else if (type != null) {
                discs.add(new RetainedDisc(slot, contents[slot].clone()));
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        DiscType cursorType = discItem.type(cursor);
        if (cursorType != null) {
            if (!loseFragments || cursorType != DiscType.FRAGMENT) {
                discs.add(new RetainedDisc(-1, cursor.clone()));
            }
            player.setItemOnCursor(ItemStack.empty());
        }
        entry.discsAtDeath = List.copyOf(discs);
        entry.selectedSlotAtDeath = inventory.getHeldItemSlot();
    }

    private static ItemStack leatherArmor(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        item.editMeta(LeatherArmorMeta.class, meta -> meta.setColor(color));
        return item;
    }

    boolean canThrow(Player player) {
        return !DiscArena.isArena(player.getWorld()) || isFighting(entrant(player));
    }

    boolean isSuddenDeath(Player player) {
        return round != null && round.suddenDeath && isFighting(entrant(player));
    }

    int cooldownTicks(Player player, int baseTicks) {
        return activeRole(player).cooldownTicks(baseTicks);
    }

    MatchRole activeRole(Player player) {
        Entrant entry = entrant(player);
        return isFighting(entry) ? entry.role : MatchRole.NONE;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldCooldown(PlayerItemGroupCooldownEvent event) {
        if (SHIELD_COOLDOWN_GROUP.equals(event.getCooldownGroup())) {
            event.setCooldown(cooldownTicks(event.getPlayer(), event.getCooldown()));
        }
    }

    void recordThrow(Player player) {
        if (isFighting(entrant(player))) {
            round.lastThrowAt = tick;
        }
    }

    void recordWardenCooldown(Player player, int ticks) {
        Entrant entry = entrant(player);
        if (entry != null && player.getWorld() == round.world) {
            entry.wardenReadyAt = tick + ticks;
        }
    }

    private boolean isFighting(Entrant entry) {
        return entry != null && round != null && entry.player.getWorld() == round.world
                && tick >= round.startsAt && entry.respawnAt == 0 && !entry.player.isDead();
    }

    private boolean canOpenMatchLoot(Player player) {
        return DiscArena.isArena(player.getWorld()) && isFighting(entrant(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !DiscArena.isArena(player.getWorld())) {
            return;
        }
        Entrant victim = entrant(player);
        if (!isFighting(victim)) {
            event.setCancelled(true);
            return;
        }
        DamageSource source = event.getDamageSource();
        double damage = event.getDamage();
        double multiplier = round.suddenDeath && isDiscDamage(source) ? SUDDEN_DEATH_DAMAGE : 1;
        if (victim.role == MatchRole.JUMPER && event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            multiplier *= 0.5;
        }
        if (victim.role == MatchRole.MATRIX && isFireDamage(source)) {
            multiplier *= 0.5;
        }
        if (source.getCausingEntity() instanceof Player attacker) {
            Entrant attacking = entrant(attacker);
            if (attacking == null || !isFighting(attacking) && source.getDamageContext().get(DiscThrow.THROW_ID).isEmpty()) {
                event.setCancelled(true);
                return;
            }
            DiscType disc = source.getDamageContext().get(DiscEffects.DISC_TYPE).orElse(null);
            if (attacking.role == MatchRole.RECONSTRUCTOR && disc == DiscType.FRAGMENT) {
                damage += RECONSTRUCTOR_FRAGMENT_BONUS;
            }
            if (attacking.role == MatchRole.SNIPER || attacking.role == MatchRole.STRIKER) {
                Vector origin = source.getDamageContext().get(DiscThrow.ATTACK_ORIGIN).orElse(null);
                if (origin != null) {
                    double distance = player.getEyeLocation().toVector().distance(origin);
                    if (attacking.role == MatchRole.SNIPER) {
                        if (disc == DiscType.SNIPE) {
                            damage += SNIPER_SNIPE_BONUS;
                        }
                        multiplier *= MatchRole.sniperDamageMultiplier(distance);
                    } else {
                        multiplier *= MatchRole.strikerDamageMultiplier(distance);
                    }
                }
            }
        }
        double adjusted = damage * multiplier;
        if (adjusted != event.getDamage()) {
            event.setDamage(adjusted);
        }
    }

    private static boolean isFireDamage(DamageSource source) {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.DAMAGE_TYPE).getTag(DamageTypeTagKeys.IS_FIRE)
                .contains(TypedKey.create(RegistryKey.DAMAGE_TYPE, source.getDamageType().getKey()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageResolved(EntityDamageEvent event) {
        DamageSource source = event.getDamageSource();
        if (round == null || !(event.getEntity() instanceof Player target)
                || !(source.getCausingEntity() instanceof Player attacker) || attacker == target) {
            return;
        }
        Entrant victim = entrant(target);
        Entrant attacking = entrant(attacker);
        if (!isFighting(victim) || attacking == null) {
            return;
        }
        if (DiscEffects.dealtDamage(event)) {
            victim.recentAttackers.putLast(attacker.getUniqueId(), tick);
        }
        if (!round.suddenDeath && isFighting(attacking) && isDiscDamage(source)
                && (event.getFinalDamage() > 0 || blockedByShield(event))) {
            round.lastExchangeAt = tick;
        }
    }

    private static boolean isDiscDamage(DamageSource source) {
        return source.getDamageContext().get(DiscEffects.DISC_TYPE).isPresent()
                || DiscDamage.BURN.matches(source.getDamageType()) && source.getDamageContext().get(DiscThrow.THROW_ID).isPresent();
    }

    @SuppressWarnings("deprecation")
    private static boolean blockedByShield(EntityDamageEvent event) {
        return event.isApplicable(DamageModifier.BLOCKING) && event.getDamage(DamageModifier.BLOCKING) < 0;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (protectTerrain(event.getEntity().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (protectTerrain(event.getBlock().getWorld())) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        if (protectTerrain(event.getEntity().getWorld())) {
            event.setFire(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosionIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.EXPLOSION && protectTerrain(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (protectTerrain(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    private static boolean protectTerrain(World world) {
        return DiscArena.isArena(world) || DiscLobby.isLobby(world);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Entrant entry = entrant(victim);
        if (entry == null || entry.respawnAt != 0) {
            return;
        }
        saveMatchDiscs(entry);
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setKeepLevel(true);
        event.deathMessage(null);
        entry.respawnAt = tick + RESPAWN_TICKS;

        DamageSource source = event.getDamageSource();
        Entrant scorer = source.getCausingEntity() instanceof Player attacker && attacker != victim ? entrant(attacker) : null;
        UUID throwId = scorer == null ? null : source.getDamageContext().get(DiscThrow.THROW_ID).orElse(null);
        if (scorer == null) {
            scorer = recentScorer(entry);
        }
        entry.recentAttackers.clear();
        if (tick >= round.startsAt && scorer != null) {
            if (scorer.role == MatchRole.SINGER && throwId != null
                    && source.getDamageContext().get(DiscEffects.DISC_TYPE).orElse(null) == DiscType.DARKNESS) {
                plugin.upgradeDarknessThrow(scorer.player, throwId);
            }
            MatchScore.Award award = scorer.score.record(victim.getUniqueId(), throwId, tick);
            announce(round, scorer.player.getName() + " が " + victim.getName() + " を撃破 +" + award.points()
                    + "点" + award.label());
        }
        victim.sendMessage("3秒後に復活します。");
        updateBoard(round);
    }

    private Entrant recentScorer(Entrant victim) {
        for (var hit : victim.recentAttackers.reversed().entrySet()) {
            if (tick - hit.getValue() > DEATH_CREDIT_TICKS) {
                break;
            }
            Entrant attacker = round.players.get(hit.getKey());
            if (attacker != null) {
                return attacker;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Entrant entry = entrant(event.getPlayer());
        if (entry != null && entry.respawnAt != 0) {
            event.setRespawnLocation(round.arena.overlook());
        } else if (entry == null && MatchPlayerState.hasPending(event.getPlayer()) && lobbyEntry != null) {
            event.setRespawnLocation(lobbyEntry.clone());
        }
    }

    @EventHandler
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        Entrant entry = entrant(player);
        if (entry != null && entry.respawnAt != 0) {
            player.setGameMode(GameMode.SPECTATOR);
        } else if (entry == null && !plugin.isFieldEditing(player) && MatchPlayerState.hasPending(player)) {
            plugin.getServer().getScheduler().runTask(plugin, () -> restorePending(player, lobbyEntry));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (round != null && tick < round.startsAt && entrant(event.getPlayer()) != null && event.hasChangedPosition()) {
            Location from = event.getFrom().clone();
            from.setYaw(event.getTo().getYaw());
            from.setPitch(event.getTo().getPitch());
            event.setTo(from);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (entrant(event.getPlayer()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> enterAfterLogin(player));
    }

    private void enterAfterLogin(Player player) {
        if (!player.isOnline() || entrant(player) != null || previews.containsKey(player.getUniqueId())
                || plugin.isFieldEditing(player)) {
            return;
        }
        try {
            Location destination = lobbyEntry;
            if (destination == null) {
                destination = prepareLobby();
                lobbyEntry = destination;
            }
            if (player.isDead() && MatchPlayerState.hasPending(player)) {
                player.spigot().respawn();
            } else if (MatchPlayerState.hasPending(player)) {
                restorePending(player, destination);
            } else if (!DiscLobby.isLobby(player.getWorld())) {
                player.teleport(destination.clone());
            }
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not move " + player.getName() + " to the lobby", error);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        leave(event.getPlayer(), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (DiscArena.isArena(event.getFrom()) && event.getFrom() != event.getPlayer().getWorld()) {
            leave(event.getPlayer(), true);
        }
    }

    void tick() {
        tick++;
        mechanisms.tickArenaRound();
        Round active = round;
        if (active == null) {
            return;
        }
        if (tick >= active.endsAt) {
            List<Entrant> entrants = List.copyOf(active.players.values());
            int highest = entrants.stream().mapToInt(entry -> entry.score.points()).max().orElse(0);
            List<Entrant> winners = entrants.stream().filter(entry -> entry.score.points() == highest).toList();
            finish(winners.size() == 1 ? winners.getFirst().player.getName() + " が " + highest + "点で勝利！"
                    : "引き分け。最高得点は " + highest + "点でした。");
            return;
        }
        if (tick == active.startsAt) {
            announce(active, "試合開始！");
            try {
                loot.begin(active.world, tick);
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not prepare this round's Disc loot boxes", error);
            }
        }
        loot.tick(tick);
        for (Entrant entry : List.copyOf(active.players.values())) {
            if (round != active) {
                return;
            }
            if (entry.respawnAt == 0 || !entry.player.isOnline()) {
                continue;
            }
            if (entry.body != null) {
                if (tick >= entry.respawnAt) {
                    respawnBot(active, entry);
                }
            } else if (entry.player.isDead()) {
                entry.player.spigot().respawn();
            } else if (tick >= entry.respawnAt) {
                respawn(active, entry);
            }
        }
        if (tick >= active.startsAt && round == active) {
            List<Player> fighters = active.players.values().stream().filter(this::isFighting)
                    .map(entry -> entry.player).toList();
            for (Entrant entry : List.copyOf(active.players.values())) {
                if (!isFighting(entry)) {
                    continue;
                }
                if (entry.role == MatchRole.RECONSTRUCTOR) {
                    resupplyFragments(active, entry);
                }
                if (entry.brain != null && tick >= entry.retryAt) {
                    try {
                        entry.brain.tick(tick, fighters);
                    } catch (RuntimeException error) {
                        entry.retryAt = tick + 100;
                        plugin.getLogger().log(Level.WARNING, "Disc BOT behavior failed for " + entry.player.getName(), error);
                    }
                }
            }
        }
        if (round == active) {
            checkSuddenDeath(active);
        }
        if (tick % 20 == 0 && round == active) {
            updateBoard(active);
        }
    }

    private void resupplyFragments(Round active, Entrant entry) {
        int intervals = (int) ((tick - active.startsAt) / RECONSTRUCTOR_RESUPPLY_TICKS);
        if (intervals > entry.fragmentResupplies) {
            int amount = (intervals - entry.fragmentResupplies) * RECONSTRUCTOR_RESUPPLY_FRAGMENTS;
            entry.fragmentResupplies = intervals;
            plugin.restoreItem(entry.player, -1, discItem.create(DiscType.FRAGMENT).asQuantity(amount));
        }
    }

    private void checkSuddenDeath(Round active) {
        if (active.suddenDeath || tick < active.startsAt
                || tick - active.lastThrowAt < NO_THROW_TICKS && tick - active.lastExchangeAt < NO_EXCHANGE_TICKS
                || active.players.values().stream().filter(this::isFighting).limit(2).count() < 2) {
            return;
        }
        active.suddenDeath = true;
        for (Entrant entry : List.copyOf(active.players.values())) {
            Player player = entry.player;
            if (!player.isOnline() || player.getWorld() != active.world) {
                continue;
            }
            clearSharedCooldown(player, entry.wardenReadyAt - tick, false);
            if (entry.role == MatchRole.MORE_THROWER) {
                clearSharedCooldown(player, entry.wardenReadyAt - tick, true);
            }
        }
        active.board.color(BossBar.Color.RED);
        updateBoard(active);
        announce(active, "サドンデス！ディスクの威力が1.5倍になりました。全員が発光し、帰還直後から再投擲できます。");
    }

    private static void clearSharedCooldown(Player player, long wardenRemaining, boolean offHand) {
        NamespacedKey sharedGroup = DiscPlugin.throwCooldownGroup(offHand);
        int shared = player.getCooldown(sharedGroup);
        if (shared <= 0) {
            return;
        }
        player.setCooldown(sharedGroup, 0);
        NamespacedKey wardenGroup = DiscItem.wardenGroup(offHand);
        int warden = player.getCooldown(wardenGroup);
        if (warden > 0 && warden <= shared) {
            player.setCooldown(wardenGroup, (int) Math.clamp(wardenRemaining, 0L, warden));
        }
    }

    private void respawn(Round active, Entrant entry) {
        Player player = entry.player;
        for (int slot = 0; slot < active.arena.capacity(); slot++) {
            Location spawn = active.arena.spawn(slot);
            boolean occupied = active.players.values().stream().anyMatch(other -> other != entry && isFighting(other)
                    && other.player.getWorld() == spawn.getWorld()
                    && spawn.distanceSquared(other.player.getLocation()) < 36);
            if (!occupied && player.teleport(spawn)) {
                entry.respawnAt = 0;
                equip(entry, true);
                return;
            }
        }
        if (player.teleport(active.arena.spawn(0))) {
            entry.respawnAt = 0;
            equip(entry, true);
        }
    }

    private void respawnBot(Round active, Entrant entry) {
        Player previous = entry.player;
        UUID id = previous.getUniqueId();
        String name = previous.getName();
        try {
            plugin.stopBot(previous);
            bots.remove(entry.body);
            Location spawn = active.arena.spawn(active.spawnCursor++ % active.arena.capacity());
            entry.body = bots.spawn(id, name, spawn);
            entry.player = entry.body.player;
            entry.brain = new BotBrain(plugin, loot, entry.body, entry.role);
            entry.respawnAt = 0;
            equip(entry, true);
        } catch (RuntimeException error) {
            active.players.remove(id);
            finishEntrant(entry, null);
            plugin.getLogger().log(Level.WARNING, "Could not respawn " + name, error);
            if (active.players.size() < 2) {
                finish("参加者が足りないため、試合を終了しました。");
            }
        }
    }

    List<Player> botPlayers() {
        return round == null ? List.of() : round.players.values().stream()
                .filter(entry -> entry.body != null).map(entry -> entry.player).toList();
    }

    private boolean leave(Player player, boolean restoreNow) {
        UUID id = player.getUniqueId();
        if (queue.remove(id)) {
            player.sendMessage("待機列から退出しました。");
            return true;
        }
        MatchPlayerState preview = previews.remove(id);
        if (preview != null) {
            if (round != null) {
                player.hideBossBar(round.board);
                round.hud.hide(player);
            }
            if (restoreNow) {
                restore(player, preview);
            }
            return true;
        }
        Round active = round;
        Entrant entry = active == null ? null : active.players.remove(id);
        if (entry == null) {
            return false;
        }

        player.hideBossBar(active.board);
        active.hud.hide(player);
        if (restoreNow) {
            restore(player, entry.original, lobbyEntry);
        }
        announce(active, player.getName() + " が試合から退出しました。");
        if (active.players.size() < 2) {
            finish("残りの参加者が2人に満たないため、試合を終了しました。");
        } else {
            updateBoard(active);
        }
        return true;
    }

    private void finish(String result) {
        Round active = round;
        round = null;
        loot.end();
        announce(active, result);
        announce(active, scoreLine(active));
        watchers(active).forEach(player -> player.hideBossBar(active.board));
        active.hud.close();
        active.players.values().forEach(entry -> finishEntrant(entry, lobbyEntry));
        try {
            mechanisms.finishArenaRound();
        } catch (RuntimeException error) {
            plugin.getLogger().log(Level.WARNING, "Could not restore Disc arena traps at round end", error);
        } finally {
            closeBotChunks(active);
        }
    }

    private static void closeBotChunks(Round ending) {
        if (ending.botChunks != null) {
            ending.botChunks.close();
        }
    }

    private void finishEntrant(Entrant entry, Location destination) {
        if (entry.body == null) {
            restore(entry.player, entry.original, destination);
        } else {
            try {
                plugin.stopBot(entry.player);
            } finally {
                bots.remove(entry.body);
            }
        }
    }

    void close() {
        if (round != null) {
            finish("サーバー停止のため試合を終了しました。");
        }
        List<UUID> spectators = List.copyOf(previews.keySet());
        for (UUID id : spectators) {
            Player player = plugin.getServer().getPlayer(id);
            MatchPlayerState state = previews.remove(id);
            if (player != null) {
                restore(player, state);
            }
        }
        queue.clear();
    }

    private void restore(Player player, MatchPlayerState state) {
        restore(player, state, null);
    }

    private void restore(Player player, MatchPlayerState state, Location destination) {
        plugin.clearPlayerEffects(player);
        plugin.returnDiscs(player);
        if (player.isOnline() && !player.isDead()) {
            try {
                boolean restored = destination == null ? state.restore(player) : state.restore(player, destination.clone());
                if (!restored) {
                    player.sendMessage("参加前の状態に戻れませんでした。/discmatch leave で再試行できます。");
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not restore match data for " + player.getName(), error);
            }
        }
    }

    private void restorePending(Player player) {
        restorePending(player, null);
    }

    private void restorePending(Player player, Location destination) {
        if (player.isOnline() && !player.isDead() && entrant(player) == null && !previews.containsKey(player.getUniqueId())) {
            try {
                boolean restored = destination == null ? MatchPlayerState.restorePending(player)
                        : MatchPlayerState.restorePending(player, destination.clone());
                if (!restored) {
                    player.sendMessage("参加前の状態に戻れませんでした。/discmatch leave で再試行できます。");
                }
            } catch (RuntimeException error) {
                plugin.getLogger().log(Level.WARNING, "Could not recover match data for " + player.getName(), error);
            }
        }
    }

    private Entrant entrant(Player player) {
        return round == null ? null : round.players.get(player.getUniqueId());
    }

    private void updateBoard(Round active) {
        long remaining = Math.max(0, active.endsAt - Math.max(tick, active.startsAt));
        active.board.progress((float) remaining / (active.endsAt - active.startsAt));
        active.board.name(Component.text(tick < active.startsAt
                ? "開始まで " + (active.startsAt - tick + 19) / 20 + "秒"
                : remainingTime(active)));
        active.hud.update(watchers(active), active.players.values().stream()
                .map(entry -> new MatchHud.Standing(entry.player.getUniqueId(), entry.player.getName(), entry.score.points()))
                .toList());
    }

    private String scoreLine(Round active) {
        String points = active.players.values().stream().map(entry -> entry.player.getName() + " " + entry.score.points())
                .collect(Collectors.joining("  /  "));
        return remainingTime(active) + "  |  " + points;
    }

    private String remainingTime(Round active) {
        long seconds = (Math.max(0, active.endsAt - Math.max(tick, active.startsAt)) + 19) / 20;
        String time = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        return active.suddenDeath ? "サドンデス  |  " + time : time;
    }

    private void announce(Round active, String message) {
        watchers(active).forEach(player -> player.sendMessage(message));
    }

    private List<Player> watchers(Round active) {
        List<Player> watchers = new ArrayList<>();
        active.players.values().stream().filter(entry -> entry.body == null).forEach(entry -> watchers.add(entry.player));
        previews.keySet().stream().map(plugin.getServer()::getPlayer)
                .filter(player -> player != null && player.getWorld() == active.world)
                .forEach(watchers::add);
        return watchers;
    }

    private static final class Round {
        private final DiscArena arena;
        private final Map<UUID, Entrant> players = new LinkedHashMap<>();
        private final BossBar board = BossBar.bossBar(Component.empty(), 1, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
        private MatchHud hud;
        private World world;
        private MatchBots.ArenaChunks botChunks;
        private long startsAt;
        private long endsAt;
        private long lastThrowAt;
        private long lastExchangeAt;
        private boolean suddenDeath;
        private int spawnCursor;

        private Round(DiscArena arena) {
            this.arena = arena;
        }
    }

    private static final class Entrant {
        private Player player;
        private final MatchPlayerState original;
        private final Color armorColor;
        private final MatchRole role;
        private int fragmentResupplies;
        private final MatchScore score = new MatchScore();
        private final LinkedHashMap<UUID, Long> recentAttackers = new LinkedHashMap<>();
        private long respawnAt;
        private long wardenReadyAt;
        private long retryAt;
        private List<RetainedDisc> discsAtDeath = List.of();
        private final List<RetainedFlight> flightsAtDeath = new ArrayList<>();
        private int selectedSlotAtDeath;
        private MatchBots.Body body;
        private BotBrain brain;

        private Entrant(Player player, MatchPlayerState original, Color armorColor, MatchRole role) {
            this.player = player;
            this.original = original;
            this.armorColor = armorColor;
            this.role = role;
        }
    }

    private record RetainedDisc(int slot, ItemStack item) { }

    private record RetainedFlight(int slot, ItemStack item, DiscType launchedType) { }
}
