package net.minecraftforge.fml.common.gameevent;

import net.minecraft.entity.player.EntityPlayer;

public class PlayerEvent {
    public final EntityPlayer player = null;

    public static class PlayerLoggedInEvent extends PlayerEvent {
    }

    public static class PlayerLoggedOutEvent extends PlayerEvent {
    }
}
