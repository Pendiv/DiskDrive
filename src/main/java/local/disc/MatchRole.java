package local.disc;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

enum MatchRole {
    NONE("役職なし", "normal", "通常Discで開始", 1),
    MULTI_THROWER("マルチスロワー", "multi", "マルチDiscを2枚装備。クールダウンが22%短い", 0.78),
    SNIPER("スナイパー", "snipe", "距離に応じて投擲ダメージが0.6〜3倍。投げたDiscの往路は1.6倍速", 1),
    SHIFTER("シフター", "normal", "盾を装備。Shiftを2回で前方に跳び、しゃがみ回復が速い", 1),
    DEMENTOR("ディメンター", "ender", "エンダーDiscで開始。Shiftを2回で視点方向へ短距離テレポート", 1),
    JUMPER("ジャンパー", "normal", "ジャンプ力が高い。空中でSpaceを押すと一度だけ追加ジャンプ。落下ダメージ半減", 1),
    ICHOR("イコル", "normal", "体力がゆっくり回復し、Shift長押しで移動が速くなる", 1),
    MORE_THROWER("モアスロワー", "normal", "両手に通常Discを装備。オフハンドの投擲クールダウンが独立", 1),
    SINGER("シンガー", "darkness", "暗闇Discで撃破すると、そのDiscがウォーデンに変化。衝撃波は再命中する", 1),
    STRIKER("ストライカー", "normal", "至近距離の投擲ダメージが2倍。距離が離れると大きく減衰", 1),
    RECONSTRUCTOR("リコンストラクター", "normal", "破片3個で開始。箱と毎分の補給で破片を獲得し、破片ダメージが0.5増加", 1),
    MATRIX("マトリクス", "laser", "レーザーDiscを2枚装備。炎ダメージ半減", 1);

    private static final NamespacedKey SELECTION = new NamespacedKey("disc", "match_role");
    private static final MatchRole[] BOT_ROLES = {MULTI_THROWER, SNIPER, SHIFTER, DEMENTOR, JUMPER, ICHOR, MORE_THROWER,
            SINGER, STRIKER, RECONSTRUCTOR, MATRIX};
    private static final double SNIPER_CLOSE_RANGE = 16;
    private static final double SNIPER_MID_RANGE = 32;
    private static final double SNIPER_FAR_RANGE = 52;
    private static final double STRIKER_CLOSE_RANGE = 3;
    private static final double STRIKER_FAR_RANGE = 15;

    private final String displayName;
    private final String starterDiscId;
    private final String description;
    private final double cooldownMultiplier;

    MatchRole(String displayName, String starterDiscId, String description, double cooldownMultiplier) {
        this.displayName = displayName;
        this.starterDiscId = starterDiscId;
        this.description = description;
        this.cooldownMultiplier = cooldownMultiplier;
    }

    static MatchRole byId(String id) {
        for (MatchRole role : values()) {
            if (role.id().equalsIgnoreCase(id)) {
                return role;
            }
        }
        return null;
    }

    static MatchRole selected(Player player) {
        MatchRole role = byId(player.getPersistentDataContainer().get(SELECTION, PersistentDataType.STRING));
        return role == null ? NONE : role;
    }

    static MatchRole randomForBot() {
        return BOT_ROLES[ThreadLocalRandom.current().nextInt(BOT_ROLES.length)];
    }

    void select(Player player) {
        player.getPersistentDataContainer().set(SELECTION, PersistentDataType.STRING, id());
    }

    String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    String displayName() {
        return displayName;
    }

    String description() {
        return description;
    }

    DiscType starterDisc() {
        DiscType disc = DiscItem.findType(starterDiscId);
        return disc == null ? DiscType.NORMAL : disc;
    }

    int starterDiscCount() {
        return switch (this) {
            case MULTI_THROWER, MORE_THROWER, MATRIX -> 2;
            default -> 1;
        };
    }

    int cooldownTicks(int baseTicks) {
        return (int) Math.round(baseTicks * cooldownMultiplier);
    }

    static double sniperDamageMultiplier(double distance) {
        if (distance <= SNIPER_MID_RANGE) {
            double progress = Math.max(0, (distance - SNIPER_CLOSE_RANGE) / (SNIPER_MID_RANGE - SNIPER_CLOSE_RANGE));
            return 0.6 + 1.4 * progress;
        }
        return 2 + Math.min(1, (distance - SNIPER_MID_RANGE) / (SNIPER_FAR_RANGE - SNIPER_MID_RANGE));
    }

    static double strikerDamageMultiplier(double distance) {
        double progress = Math.clamp((distance - STRIKER_CLOSE_RANGE) / (STRIKER_FAR_RANGE - STRIKER_CLOSE_RANGE), 0, 1);
        return 2 - 1.8 * progress;
    }
}
