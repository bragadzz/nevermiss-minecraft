package net.minecraft.server;

import com.google.common.util.concurrent.ListenableFuture;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.management.PlayerList;

public abstract class MinecraftServer implements ICommandSender {
    // getCommandManager
    public ICommandManager func_71187_D() {
        return null;
    }

    // isServerInOnlineMode
    public boolean func_71266_T() {
        return false;
    }

    // getMinecraftVersion
    public String func_71249_w() {
        return null;
    }

    // getPlayerList
    public PlayerList func_184103_al() {
        return null;
    }

    // addScheduledTask
    public ListenableFuture<Object> func_152344_a(Runnable tarefa) {
        return null;
    }

    // isCallingFromMinecraftThread
    public boolean func_152345_ab() {
        return false;
    }
}
