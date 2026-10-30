package net.minecraft.command;

import net.minecraft.server.MinecraftServer;

public interface ICommand {
    // getName
    String func_71517_b();

    // getUsage
    String func_71518_a(ICommandSender quem);

    // execute
    void func_184881_a(MinecraftServer servidor, ICommandSender quem, String[] args) throws CommandException;

    // checkPermission
    boolean func_184882_a(MinecraftServer servidor, ICommandSender quem);
}
