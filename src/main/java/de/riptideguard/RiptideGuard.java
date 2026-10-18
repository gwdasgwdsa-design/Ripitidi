package de.riptideguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Blockt Riptide-Fliegen ohne Wasser/Regen.
 * Der Server lehnt den Riptide ab (er prueft isInWaterOrRain serverseitig),
 * aber der Client bewegt sich trotzdem. Wir erkennen die unplausible Bewegung,
 * setzen den Spieler zurueck und bannen ihn bei jedem Verstoss kurz.
 */
public final class RiptideGuard extends JavaPlugin implements Listener {

    private double maxHorizontal;
    private double maxUp;
    private long graceMs;
    private long tridentWindowMs;
    private long resetMs;
    private int banSeconds;
    private String banReason;

    private final Map<UUID, Long> lastLegit = new HashMap<>();
    private final Map<UUID, Long> lastTrident = new HashMap<>();
    private final Map<UUID, Long> lastViolation = new HashMap<>();
    private final Map<UUID, Integer> violations = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        maxHorizontal = getConfig().getDouble("max-horizontal-per-move", 1.0);
        maxUp = getConfig().getDouble("max-up-per-move", 1.2);
        graceMs = getConfig().getLong("grace-ms", 3000);
        tridentWindowMs = getConfig().getLong("trident-window-ms", 1500);
        resetMs = getConfig().getLong("violation-reset-seconds", 60) * 1000L;
        banSeconds = getConfig().getInt("ban-duration-seconds", 1800);
        banReason = getConfig().getString("ban-reason",
                "Riptide ohne Regen/Wasser ist nicht erlaubt. Gebannt fuer 30 Minuten.");
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;

        Player p = e.getPlayer();
        if (p.hasPermission("riptideguard.bypass")) return;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;
        if (p.getAllowFlight()) return;

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (holdsRiptideTrident(p)) {
            lastTrident.put(id, now);
        }

        // Legitime Situationen: Schonfrist starten und nichts pruefen
        if (p.isInWaterOrRain() || p.isRiptiding() || p.isGliding() || p.isInsideVehicle()) {
            lastLegit.put(id, now);
            return;
        }
        if (now - lastLegit.getOrDefault(id, 0L) < graceMs) return;

        // Nur pruefen, wenn kuerzlich ein Riptide-Dreizack in der Hand war
        Long held = lastTrident.get(id);
        if (held == null || now - held > tridentWindowMs) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;

        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());
        double up = to.getY() - from.getY();

        if (horizontal <= maxHorizontal && up <= maxUp) return;

        e.setCancelled(true);
        flag(p, horizontal, up, now);
    }

    private void flag(Player p, double horizontal, double up, long now) {
        UUID id = p.getUniqueId();

        if (now - lastViolation.getOrDefault(id, 0L) > resetMs) {
            violations.put(id, 0);
        }
        int v = violations.merge(id, 1, Integer::sum);
        lastViolation.put(id, now);

        String msg = String.format("[RiptideGuard] %s: Riptide ohne Regen/Wasser (h=%.2f, up=%.2f, Verstoss #%d)",
                p.getName(), horizontal, up, v);
        getLogger().warning(msg);

        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("riptideguard.notify")) {
                staff.sendMessage(Component.text(msg));
            }
        }

        if (banSeconds > 0) {
            Date expires = new Date(now + banSeconds * 1000L);
            p.ban(banReason, expires, "RiptideGuard");
            p.kick(Component.text(banReason));
        }
    }

    private boolean holdsRiptideTrident(Player p) {
        return isRiptideTrident(p.getInventory().getItemInMainHand())
                || isRiptideTrident(p.getInventory().getItemInOffHand());
    }

    private boolean isRiptideTrident(ItemStack item) {
        return item != null
                && item.getType() == Material.TRIDENT
                && item.containsEnchantment(Enchantment.RIPTIDE);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        lastLegit.remove(id);
        lastTrident.remove(id);
        lastViolation.remove(id);
        violations.remove(id);
    }
}
