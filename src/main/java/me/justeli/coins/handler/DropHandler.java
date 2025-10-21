package me.justeli.coins.handler;

import me.justeli.coins.Coins;
import me.justeli.coins.config.Config;
import me.justeli.coins.item.CoinUtil;
import me.justeli.coins.util.PermissionNode;
import me.justeli.coins.util.Util;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Optional;
import java.util.SplittableRandom;

public final class DropHandler
    implements Listener
{
    private final Coins coins;
    private final NamespacedKey playerDamage;

    public DropHandler (Coins coins)
    {
        this.coins = coins;
        this.playerDamage = new NamespacedKey(coins, "coins-player-damage");
    }

    private final HashMap<Location, Integer> locationAmountCache = new HashMap<>();
    private final HashMap<Location, Long> locationLastTimeCache = new HashMap<>();

    private static final SplittableRandom RANDOM = new SplittableRandom();

    @EventHandler (priority = EventPriority.HIGH)
    public void onEntityDeath (EntityDeathEvent event)
    {
        if (this.coins.isDisabled())
            return;

        LivingEntity dead = event.getEntity();

        if (Util.isDisabledHere(dead.getWorld()))
            return;

        if (this.coins.mmHook().isPresent() && Config.DISABLE_MYTHIC_MOB_HANDLING && this.coins.mmHook().get().isMythicMob(dead))
            return;

        if (Config.LOSE_ON_DEATH && dead instanceof Player)
        {
            loseOnDeathHandler((Player) dead);
        }

        if (Config.DROP_WITH_ANY_DEATH)
        {
            mobChecker(dead, null);
            return;
        }

        AttributeInstance maxHealth = dead.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth == null)
            return;

        if (Config.PERCENTAGE_PLAYER_HIT > 0 && getPlayerDamage(dead) / maxHealth.getValue() < Config.PERCENTAGE_PLAYER_HIT)
            return;

        Optional<Player> attacker = Util.getRootDamage(dead);
        if (!attacker.isPresent())
            return;

        mobChecker(dead, attacker.get());
    }

    private void loseOnDeathHandler (@NotNull Player dead)
    {
        double random = Util.getRandomTakeAmount();
        this.coins.economy().balance(dead.getUniqueId(), balance ->
        {
            if (balance <= 0)
                return;

            double take = Util.round(
                Config.TAKE_PERCENTAGE
                    ? (random / 100) * balance
                    : random
            );

            if (take <= 0)
                return;

            this.coins.economy().withdraw(dead.getUniqueId(), take, () ->
            {
                Util.send(Config.DEATH_MESSAGE_POSITION, dead, Config.DEATH_MESSAGE, take);

                if (Config.DROP_ON_DEATH && dead.getLocation().getWorld() != null)
                {
                    dead.getWorld().dropItem(
                        dead.getLocation(),
                        this.coins.getCreateCoin().other().data(CoinUtil.COINS_WORTH, take).build()
                    );
                }
            });
        });
    }

    private void mobChecker (@NotNull Entity dead, @Nullable Player attacker)
    {
        if (Config.PREVENT_SPLITS && this.coins.getUnfairMobHandler().fromSplit(dead))
            return;

        if (!Config.SPAWNER_DROP && this.coins.getUnfairMobHandler().fromSpawner(dead))
        {
            if (attacker == null || !attacker.hasPermission(PermissionNode.SPAWNER))
                return;
        }

        if (Config.MOB_MULTIPLIER.containsKey(dead.getType()) && !(dead instanceof Player))
        {
            mobHandler(dead, attacker);
            return;
        }

        if (!Config.HOSTILE_DROP && Util.isHostile(dead))
            return;

        if (!Config.PASSIVE_DROP && Util.isPassive(dead))
            return;

        if (!Config.PLAYER_DROP && dead instanceof Player)
            return;

        if (dead instanceof Player)
        {
            this.coins.economy().balance(dead.getUniqueId(), balance -> { if (balance > 0) mobHandler(dead, attacker); });
            return;
        }

        mobHandler(dead, attacker);
    }

    private void mobHandler (@NotNull Entity dead, @Nullable Player attacker)
    {
        if (Config.PREVENT_ALTS && attacker != null && dead instanceof Player)
        {
            Player victim = (Player) dead;

            InetSocketAddress address1 = attacker.getAddress();
            InetSocketAddress address2 = victim.getAddress();

            if (address1 != null && address2 != null && address1.getAddress().getHostAddress().equals(address2.getAddress().getHostAddress()))
                return;
        }

        if (RANDOM.nextDouble() > Config.DROP_CHANCE)
            return;

        if (!isLocationAvailableAndSet(dead))
            return;

        drop(
            Config.MOB_MULTIPLIER.getOrDefault(dead.getType(), 1),
            attacker,
            dead.getLocation(),
            Enchantment.LOOT_BONUS_MOBS
        );
    }

    private boolean isLocationAvailableAndSet (Entity dead)
    {
        if (Config.LIMIT_FOR_LOCATION < 1)
            return true;

        Location location = dead.getLocation().getBlock().getLocation();
        long previousTime = this.locationLastTimeCache.computeIfAbsent(location, empty -> 0L);

        if (previousTime > System.currentTimeMillis() - 3600000 * Config.LOCATION_LIMIT_HOURS)
        {
            // within the past hour
            int killAmount = this.locationAmountCache.computeIfAbsent(location, empty -> 0);

            this.locationAmountCache.put(location, killAmount + 1);
            this.locationLastTimeCache.put(location, System.currentTimeMillis());

            return killAmount < Config.LIMIT_FOR_LOCATION;
        }

        this.locationAmountCache.put(location, 1);
        this.locationLastTimeCache.put(location, System.currentTimeMillis());
        return true;
    }

    @EventHandler (ignoreCancelled = true,
                   priority = EventPriority.MONITOR)
    public void onBlockBreak (BlockBreakEvent event)
    {
        if (this.coins.isDisabled() || Util.isDisabledHere(event.getBlock().getWorld()))
            return;

        if (Config.MINE_PERCENTAGE == 0)
            return;

        if (event.getPlayer().getGameMode() != GameMode.SURVIVAL || blockDropsSameItem(event))
            return;

        int multiplier = Config.BLOCK_DROPS.computeIfAbsent(event.getBlock().getType(), empty -> 0);
        if (multiplier == 0)
            return;

        if (RANDOM.nextDouble() > Config.MINE_PERCENTAGE)
            return;

        this.coins.sync(1, () -> drop(
            multiplier,
            event.getPlayer(),
            event.getBlock().getLocation().clone().add(0.5, 0.5, 0.5),
            Enchantment.LOOT_BONUS_BLOCKS
        ));
    }

    private boolean blockDropsSameItem (BlockBreakEvent event)
    {
        Material type = event.getBlock().getType();
        return event.getBlock().getDrops(event.getPlayer().getInventory().getItemInMainHand())
            .stream()
            .anyMatch(item -> item.getType() == type);
    }

    private void drop (int amount, @Nullable Player player, @NotNull Location location, @NotNull Enchantment enchantment)
    {
        if (location.getWorld() == null)
            return;

        double increment = 1;

        // keep your looting/fortune increment logic
        if (player != null && Config.ENCHANT_INCREMENT > 0)
        {
            int lootingLevel = player.getInventory().getItemInMainHand().getEnchantmentLevel(enchantment);
            if (lootingLevel > 0)
            {
                increment += lootingLevel * Config.ENCHANT_INCREMENT;
            }
        }

        // If drop each coin, "amount" becomes the number of $1 coins to drop; increment becomes 1
        if (Config.DROP_EACH_COIN)
        {
            amount *= (int) ((Util.getRandomMoneyAmount() + 0.5) * increment);
            increment = 1;
        }

        // player multiplier as before
        if (player != null)
        {
            amount *= (int) Util.getMultiplier(player);
        }

        // Final total "dollars" to represent with denominations
        int total = (int) Math.round(amount * increment);
        if (total <= 0) return;

        // --- Build greedy denomination list from @ConfigEntry map ---
        // Expecting: denominations: {"1000":401000, "50":400050, "1":4410002}
        java.util.List<java.util.Map.Entry<Integer,Integer>> denoms = new java.util.ArrayList<>();
        if (Config.DENOMINATIONS != null && !Config.DENOMINATIONS.isEmpty()) {
            for (java.util.Map.Entry<String,Integer> e : Config.DENOMINATIONS.entrySet()) {
                int v;
                try { v = Integer.parseInt(e.getKey()); } catch (Exception ex) { continue; }
                if (v > 0) denoms.add(new java.util.AbstractMap.SimpleEntry<>(v, e.getValue() == null ? 0 : e.getValue()));
            }
        }
        if (denoms.isEmpty()) {
            // sane defaults if config missing
            denoms.add(new java.util.AbstractMap.SimpleEntry<>(1000, 401000));
            denoms.add(new java.util.AbstractMap.SimpleEntry<>(50,   400050));
            denoms.add(new java.util.AbstractMap.SimpleEntry<>(1,    400001));
        }
        // Sort DESC by value (greedy)
        denoms.sort((a,b) -> Integer.compare(b.getKey(), a.getKey()));

        // --- Greedy breakdown & dropping ---
        for (java.util.Map.Entry<Integer,Integer> d : denoms) {
            int value = d.getKey();
            int cmd   = d.getValue();
            if (value <= 0 || total <= 0) continue;

            int qty = total / value;
            if (qty <= 0) continue;

            // Drop "qty" stacks of this denomination
            for (int i = 0; i < qty; i++) {
                // Build your base coin using existing API, then stamp CMD for the denom
                org.bukkit.inventory.ItemStack stack = this.coins.getCreateCoin()
                    .other()
                    .data(CoinUtil.COINS_WORTH, (double) value) // store the worth if you already do this
                    .build();

                org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    if (cmd > 0) meta.setCustomModelData(cmd);
                    stack.setItemMeta(meta);
                }

                org.bukkit.entity.Item it = location.getWorld().dropItemNaturally(location, stack);
                it.setPickupDelay(10);
            }

            total -= qty * value;
        }

        // Any leftover that doesn't match a denom (shouldn’t happen if 1 exists) → drop as $1s without a CMD
        while (total-- > 0) {
            org.bukkit.inventory.ItemStack stack = this.coins.getCreateCoin()
                .other()
                .data(CoinUtil.COINS_WORTH, 1D)
                .build();
            org.bukkit.entity.Item it = location.getWorld().dropItemNaturally(location, stack);
            it.setPickupDelay(10);
        }
    }


    @EventHandler (priority = EventPriority.LOW)
    public void onEntityDamage (EntityDamageByEntityEvent event)
    {
        if (!Util.getRootDamage(event).isPresent())
            return;

        double playerDamage = getPlayerDamage(event.getEntity());
        event.getEntity().getPersistentDataContainer().set(
            this.playerDamage,
            PersistentDataType.DOUBLE,
            playerDamage + event.getFinalDamage()
        );
    }

    private double getPlayerDamage (@NotNull Entity entity)
    {
        return entity.getPersistentDataContainer().getOrDefault(this.playerDamage, PersistentDataType.DOUBLE, 0D);
    }
}
