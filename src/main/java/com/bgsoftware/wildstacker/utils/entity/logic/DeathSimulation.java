package com.bgsoftware.wildstacker.utils.entity.logic;

import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.enums.EntityFlag;
import com.bgsoftware.wildstacker.api.enums.StackSplit;
import com.bgsoftware.wildstacker.api.enums.UnstackResult;
import com.bgsoftware.wildstacker.api.objects.StackedEntity;
import com.bgsoftware.wildstacker.hooks.JobsHook;
import com.bgsoftware.wildstacker.hooks.McMMOHook;
import com.bgsoftware.wildstacker.objects.WStackedEntity;
import com.bgsoftware.wildstacker.utils.GeneralUtils;
import com.bgsoftware.wildstacker.utils.entity.EntityUtils;
import com.bgsoftware.wildstacker.utils.items.ItemUtils;
import com.bgsoftware.wildstacker.utils.legacy.EntityTypes;
import com.bgsoftware.wildstacker.utils.pair.Pair;
import com.bgsoftware.wildstacker.utils.statistics.StatisticsUtils;
import com.bgsoftware.wildstacker.utils.threads.Executor;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class DeathSimulation {

    private static final WildStackerPlugin plugin = WildStackerPlugin.getPlugin();

    private final static Enchantment SWEEPING_EDGE = Enchantment.getByName("SWEEPING_EDGE");
    private static final Map<EntityDamageEvent.DamageModifier, ? extends Function<? super Double, Double>> DAMAGE_MODIFIERS_FUNCTIONS =
            Maps.newEnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, Functions.constant(-0.0D)));
    private static boolean sweepingEdgeHandled = false;

    private DeathSimulation() {
    }

    public static Result simulateDeath(StackedEntity stackedEntity, EntityDamageEvent.DamageCause lastDamageCause,
                                       ItemStack killerTool, Player killer, Entity entityKiller, boolean creativeMode,
                                       double originalDamage, double finalDamage, Set<UUID> noDeathEvent) {
        if (!plugin.getSettings().entitiesStackingEnabled && stackedEntity.getStackAmount() <= 1)
            return new Result(false, -1);

        LivingEntity livingEntity = stackedEntity.getLivingEntity();

        if (lastDamageCause != EntityDamageEvent.DamageCause.VOID &&
                plugin.getNMSAdapter().handleTotemOfUndying(livingEntity)) {
            return new Result(true, -1);
        }

        if(stackedEntity.hasFlag(EntityFlag.ATTACKED_ENTITY))
            return new Result(true, -1);

        Pair<Integer, Double> spreadDamageResult = checkForSpreadDamage(stackedEntity,
                stackedEntity.isInstantKill(lastDamageCause), finalDamage, killerTool);

        int entitiesToKill = spreadDamageResult.getKey();
        double damageToNextStack = spreadDamageResult.getValue();

        int fireTicks = livingEntity.getFireTicks();

        Result result = new Result(false, 0);

        if(handleFastKill(livingEntity, killer))
            result.cancelEvent = true;

        if (killer != null)
            McMMOHook.handleCombat(killer, entityKiller, livingEntity, finalDamage);

        livingEntity.setHealth(livingEntity.getMaxHealth() - damageToNextStack);

        //Villager was killed by a zombie - should be turned into a zombie villager.
        if(checkForZombieVillager(stackedEntity, entityKiller))
            return result;

        int originalAmount = stackedEntity.getStackAmount();

        if (stackedEntity.runUnstack(entitiesToKill, entityKiller) != UnstackResult.SUCCESS)
            return result;

        stackedEntity.setFlag(EntityFlag.ATTACKED_ENTITY, true);

        int unstackAmount = originalAmount - stackedEntity.getStackAmount();

        EntityUtils.setKiller(livingEntity, killer);

        if (plugin.getSettings().keepFireEnabled && livingEntity.getFireTicks() > -1)
            livingEntity.setFireTicks(160);

        giveStatisticsToKiller(killer, unstackAmount, stackedEntity);

        // Handle sweeping edge enchantment
        if (!sweepingEdgeHandled && result.cancelEvent && killerTool != null && killer != null) {
            try {
                sweepingEdgeHandled = true;
                plugin.getNMSAdapter().handleSweepingEdge(killer, killerTool, stackedEntity.getLivingEntity(), originalDamage);
            } finally {
                sweepingEdgeHandled = false;
            }
        }

        //Decrease durability when next-stack-knockback is false
        if (result.cancelEvent && killerTool != null && !creativeMode && !ItemUtils.isUnbreakable(killerTool))
            reduceKillerToolDurability(killerTool, killer);

        EntityDamageEvent clonedEvent = createDamageEvent(livingEntity, lastDamageCause, originalDamage, entityKiller);
        Location dropLocation = livingEntity.getLocation().add(0, 0.5, 0);

        Executor.async(() -> {
            livingEntity.setLastDamageCause(clonedEvent);
            livingEntity.setFireTicks(fireTicks);

            int lootBonusLevel = killerTool == null ? 0 : killerTool.getEnchantmentLevel(Enchantment.LOOT_BONUS_MOBS);
            List<ItemStack> drops = stackedEntity.getDrops(lootBonusLevel, plugin.getSettings().multiplyDrops ? unstackAmount : 1);
            int droppedExp = stackedEntity.getExp(plugin.getSettings().multiplyExp ? unstackAmount : 1, 0);

            Executor.sync(() -> {
                plugin.getNMSAdapter().setEntityDead(livingEntity, true);
                ((WStackedEntity) stackedEntity).setDeadFlag(true);

                // Setting the stack amount of the entity to the unstack amount.
                int realStackAmount = stackedEntity.getStackAmount();
                stackedEntity.setStackAmount(unstackAmount, false);

                McMMOHook.updateCachedName(livingEntity);
                boolean isMcMMOSpawnedEntity = McMMOHook.isSpawnerEntity(livingEntity);

                // I set the health to 0, so it will be 0 in the EntityDeathEvent
                // Some plugins, such as MyPet, check for that value
                double originalHealth = livingEntity.getHealth();
                plugin.getNMSAdapter().setHealthDirectly(livingEntity, 0);

                boolean spawnDuplicate = false;
                List<ItemStack> finalDrops;
                int finalExp;

                if (!noDeathEvent.contains(livingEntity.getUniqueId())) {
                    EntityDeathEvent entityDeathEvent = new EntityDeathEvent(livingEntity, new ArrayList<>(drops), droppedExp);
                    Bukkit.getPluginManager().callEvent(entityDeathEvent);
                    finalDrops = entityDeathEvent.getDrops();
                    finalExp = entityDeathEvent.getDroppedExp();
                } else {
                    spawnDuplicate = true;
                    noDeathEvent.remove(livingEntity.getUniqueId());
                    finalDrops = drops;
                    Integer expToDropFlag = stackedEntity.getFlag(EntityFlag.EXP_TO_DROP);
                    finalExp = expToDropFlag == null ? 0 : expToDropFlag;
                    stackedEntity.removeFlag(EntityFlag.EXP_TO_DROP);
                }

                // Restore all values.
                plugin.getNMSAdapter().setEntityDead(livingEntity, false);
                plugin.getNMSAdapter().setHealthDirectly(livingEntity, originalHealth);
                stackedEntity.setStackAmount(realStackAmount, false);

                // If setting this to ender dragons, the death animation doesn't happen for an unknown reason.
                // Cannot revert to original death event neither. This fixes death animations for all versions.
                if (livingEntity.getType() != EntityType.ENDER_DRAGON)
                    livingEntity.setLastDamageCause(null);

                if (isMcMMOSpawnedEntity)
                    McMMOHook.updateSpawnedEntity(livingEntity);

                McMMOHook.cancelRuptureTask(livingEntity);

                JobsHook.updateSpawnReason(livingEntity, stackedEntity.getSpawnCause());

                finalDrops.removeIf(itemStack -> itemStack == null || itemStack.getType() == Material.AIR);

                // Multiply items that weren't added in the first place
                // We should call this only when the event was called - aka finalDrops != drops.
                if (plugin.getSettings().multiplyDrops && finalDrops != drops) {
                    subtract(drops, finalDrops).forEach(itemStack -> itemStack.setAmount(itemStack.getAmount() * unstackAmount));
                }

                finalDrops.forEach(itemStack -> ItemUtils.dropItem(itemStack, dropLocation));

                if (finalExp > 0) {
                    if (GeneralUtils.contains(plugin.getSettings().entitiesAutoExpPickup, stackedEntity) && livingEntity.getKiller() != null) {
                        EntityUtils.giveExp(livingEntity.getKiller(), finalExp);
                        if (plugin.getSettings().entitiesExpPickupSound != null)
                            livingEntity.getKiller().playSound(livingEntity.getLocation(),
                                    plugin.getSettings().entitiesExpPickupSound, 0.1F, 0.1F);
                    } else {
                        EntityUtils.spawnExp(livingEntity.getLocation(), finalExp);
                    }
                }

                attemptJoinRaid(killer, livingEntity);

                ((WStackedEntity) stackedEntity).setDeadFlag(false);

                stackedEntity.removeFlag(EntityFlag.ATTACKED_ENTITY);

                if (!stackedEntity.hasFlag(EntityFlag.REMOVED_ENTITY) && (livingEntity.getHealth() <= 0 ||
                        (spawnDuplicate && stackedEntity.getStackAmount() > 1))) {
                    stackedEntity.spawnDuplicate(stackedEntity.getStackAmount());
                    Executor.sync(stackedEntity::remove, 1L);
                }
            });
        });

        return result;
    }

    private static Pair<Integer, Double> checkForSpreadDamage(StackedEntity stackedEntity,
                                                              boolean instantKill, double finalDamage,
                                                              ItemStack damagerTool){
        int entitiesToKill;
        double damageToNextStack;

        if (plugin.getSettings().spreadDamage && !instantKill) {
            double dealtDamage = finalDamage;

            if (SWEEPING_EDGE != null && damagerTool != null) {
                int sweepingEdgeLevel = damagerTool.getEnchantmentLevel(SWEEPING_EDGE);
                if (sweepingEdgeLevel > 0)
                    dealtDamage = 1 + finalDamage * ((double) sweepingEdgeLevel / (sweepingEdgeLevel + 1));
            }

            double entityHealth = stackedEntity.getHealth();
            double entityMaxHealth = stackedEntity.getLivingEntity().getMaxHealth();

            double leftDamage = Math.max(0, dealtDamage - entityHealth);

            entitiesToKill = Math.min(stackedEntity.getStackAmount(), 1 + (int) (leftDamage / entityMaxHealth));
            damageToNextStack = leftDamage % entityMaxHealth;
        } else {
            entitiesToKill = Math.min(stackedEntity.getStackAmount(),
                    instantKill ? stackedEntity.getStackAmount() : stackedEntity.getDefaultUnstack());
            damageToNextStack = 0;
        }

        return new Pair<>(entitiesToKill, damageToNextStack);
    }

    private static boolean handleFastKill(LivingEntity livingEntity, Player damager){
        if (plugin.getSettings().entitiesFastKill) {

            if (damager != null) {
                // We make sure the entity has no damage ticks, so it can always be hit.
                livingEntity.setMaximumNoDamageTicks(0);
            }

            // We make sure the entity doesn't get any knockback by setting the velocity to 0.
            Executor.sync(() -> livingEntity.setVelocity(new Vector()), 1L);

            return true;
        }

        return false;
    }

    private static boolean checkForZombieVillager(StackedEntity stackedEntity, Entity entityDamager){
        LivingEntity livingEntity = stackedEntity.getLivingEntity();

        if(livingEntity.getType() != EntityType.VILLAGER || !(entityDamager instanceof Zombie))
            return false;

        switch (livingEntity.getWorld().getDifficulty()) {
            case NORMAL:
                if(!ThreadLocalRandom.current().nextBoolean())
                    return false;
                break;
            case EASY:
            case PEACEFUL:
                return false;
        }

        Zombie zombieVillager = plugin.getNMSAdapter().spawnZombieVillager((Villager) livingEntity);

        if(zombieVillager == null)
            return false;

        StackedEntity stackedZombie = WStackedEntity.of(zombieVillager);

        if (StackSplit.VILLAGER_INFECTION.isEnabled()) {
            stackedEntity.runUnstack(1, entityDamager);
        } else {
            stackedZombie.setStackAmount(stackedEntity.getStackAmount(), true);
            stackedEntity.remove();
        }

        stackedZombie.updateName();
        stackedZombie.runStackAsync(null);

        return true;
    }

    private static void giveStatisticsToKiller(Player killer, int unstackAmount, StackedEntity stackedEntity){
        if(killer == null)
            return;

        EntityType victimType = stackedEntity.getType();

        try {
            StatisticsUtils.incrementStatistic(killer, Statistic.MOB_KILLS, unstackAmount);
            StatisticsUtils.incrementStatistic(killer, Statistic.KILL_ENTITY, victimType, unstackAmount);
        } catch (IllegalArgumentException ignored) {}

        //Monster Hunter
        grandAchievement(killer, victimType, "KILL_ENEMY");
        grandAchievement(killer, victimType, "adventure/kill_a_mob");

        //Monsters Hunted
        grandAchievement(killer, victimType, "adventure/kill_all_mobs");

        //Sniper Duel
        if (stackedEntity.getWorld().equals(killer.getWorld()) &&
                killer.getLocation().distanceSquared(stackedEntity.getLocation()) >= 2500) {
            grandAchievement(killer, "", "SNIPE_SKELETON");
            grandAchievement(killer, "killed_skeleton", "adventure/sniper_duel");
        }
    }

    private static void reduceKillerToolDurability(ItemStack damagerTool, Player killer){
        int damage = ItemUtils.isSword(damagerTool.getType()) ? 1 : ItemUtils.isTool(damagerTool.getType()) ? 2 : 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (damage > 0) {
            int unbreakingLevel = damagerTool.getEnchantmentLevel(Enchantment.DURABILITY);
            int damageDecrease = 0;

            for (int i = 0; unbreakingLevel > 0 && i < damage; i++) {
                if (random.nextInt(unbreakingLevel + 1) > 0)
                    damageDecrease++;
            }

            damage -= damageDecrease;

            if (damage > 0) {
                if (damagerTool.getDurability() + damage > damagerTool.getType().getMaxDurability())
                    killer.setItemInHand(new ItemStack(Material.AIR));
                else
                    damagerTool.setDurability((short) (damagerTool.getDurability() + damage));
            }
        }
    }

    private static void attemptJoinRaid(Player killer, LivingEntity livingEntity){
        if(killer == null || !EntityTypes.fromEntity(livingEntity).isRaider())
            return;

        org.bukkit.entity.Raider raider = (org.bukkit.entity.Raider) livingEntity;

        if (raider.isPatrolLeader()) {
            killer.addPotionEffect(new PotionEffect(
                    PotionEffectType.getByName("BAD_OMEN"),
                    120000,
                    EntityUtils.getBadOmenAmplifier(killer),
                    false
            ));
        }

        plugin.getNMSAdapter().attemptJoinRaid(killer, raider);
    }

    private static EntityDamageEvent createDamageEvent(Entity entity, EntityDamageEvent.DamageCause damageCause, double damage, Entity damager) {
        Map<EntityDamageEvent.DamageModifier, Double> damageModifiers = Maps.newEnumMap(ImmutableMap.of(EntityDamageEvent.DamageModifier.BASE, damage));
        if (damager == null) {
            return new EntityDamageEvent(entity, damageCause, damageModifiers, DAMAGE_MODIFIERS_FUNCTIONS);
        } else {
            return new EntityDamageByEntityEvent(damager, entity, damageCause, damageModifiers, DAMAGE_MODIFIERS_FUNCTIONS);
        }
    }

    private static void grandAchievement(Player killer, EntityType entityType, String name) {
        try {
            plugin.getNMSAdapter().grandAchievement(killer, entityType, name);
        } catch (Throwable ignored) {
        }
    }

    private static void grandAchievement(Player killer, String criteria, String name) {
        try {
            plugin.getNMSAdapter().grandAchievement(killer, criteria, name);
        } catch (Throwable ignored) {
        }
    }

    private static List<ItemStack> subtract(List<ItemStack> list1, List<ItemStack> list2) {
        List<ItemStack> toReturn = new ArrayList<>(list2);
        toReturn.removeAll(list1);
        return toReturn;
    }

    public static final class Result {

        private boolean cancelEvent;
        private double eventDamage;

        public Result(boolean cancelEvent, double eventDamage) {
            this.cancelEvent = cancelEvent;
            this.eventDamage = eventDamage;
        }

        public boolean isCancelEvent() {
            return cancelEvent;
        }

        public double getEventDamage() {
            return eventDamage;
        }

    }

}
