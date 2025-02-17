package com.bgsoftware.wildstacker.hooks;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandFlag;
import com.bgsoftware.wildstacker.WildStackerPlugin;
import com.bgsoftware.wildstacker.api.events.EntityStackEvent;
import com.bgsoftware.wildstacker.api.events.SpawnerStackedEntitySpawnEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class SuperiorSkyblockHook {

    private static IslandFlag ENTITIES_STACKING;

    public static void register(WildStackerPlugin plugin) {
        if (plugin.getSettings().superiorSkyblockHook) {
            if (ENTITIES_STACKING == null){
               WildStackerPlugin.log("&cCouldn't register a custom island-flag into SuperiorSkyblock - open an issue on github.");
               return;
            }

            SuperiorSkyblockAPI.getSuperiorSkyblock().getMenus().updateSettings(ENTITIES_STACKING);
            plugin.getServer().getPluginManager().registerEvents(new EntityListener(), plugin);
        }
    }

    public static void registerIslandFlag(){
        IslandFlag.register("ENTITIES_STACKING");
        ENTITIES_STACKING = IslandFlag.getByName("ENTITIES_STACKING");
    }

    private static class EntityListener implements Listener {

        @EventHandler
        public void onEntityStack(EntityStackEvent e) {
            Island island = SuperiorSkyblockAPI.getIslandAt(e.getTarget().getLocation());
            if (island != null && !island.hasSettingsEnabled(ENTITIES_STACKING))
                e.setCancelled(true);
        }

        @EventHandler
        public void onEntityStackedSpawn(SpawnerStackedEntitySpawnEvent e) {
            Island island = SuperiorSkyblockAPI.getIslandAt(e.getSpawner().getLocation());
            if (island != null && !island.hasSettingsEnabled(ENTITIES_STACKING))
                e.setShouldBeStacked(false);
        }

    }

}
