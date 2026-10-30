package net.minecraftforge.fml.common.event;

import net.minecraft.command.ICommand;
import net.minecraft.server.MinecraftServer;

public class FMLServerStartingEvent {
    public MinecraftServer getServer() {
        return null;
    }

    public void registerServerCommand(ICommand command) {
    }
}
