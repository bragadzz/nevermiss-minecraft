package net.minecraft.command;

import net.minecraft.server.MinecraftServer;

public abstract class CommandBase implements ICommand {
    // getRequiredPermissionLevel
    public int func_82362_a() {
        return 4;
    }

    @Override
    public boolean func_184882_a(MinecraftServer servidor, ICommandSender quem) {
        return false;
    }
}
