package com.bgsoftware.wildstacker.hooks;

import com.github.intellectualsites.plotsquared.api.PlotAPI;
import com.github.intellectualsites.plotsquared.plot.object.Plot;
import com.sk89q.worldedit.math.BlockVector2;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public final class ClaimsProvider_PlotSquared implements ClaimsProvider {

    private PlotAPI API = new PlotAPI();

    @Override
    public boolean hasClaimAccess(Player player, Location location) {
        Plot plot = API.getChunkManager().hasPlot(location.getWorld().getName(), BlockVector2.at(location.getChunk().getX(), location.getChunk().getZ()));
        Plot playerLocationPlot = API.getChunkManager().hasPlot(player.getWorld().getName(),
                BlockVector2.at(player.getLocation().getChunk().getX(), player.getLocation().getChunk().getZ()));
        if (plot != null && playerLocationPlot != null && !plot.getId().equals(playerLocationPlot.getId()))
            return false;
        return plot == null || player.hasPermission("plots.admin.build.other") ||
                plot.isOwner(player.getUniqueId()) || plot.isAdded(player.getUniqueId());
    }

}
