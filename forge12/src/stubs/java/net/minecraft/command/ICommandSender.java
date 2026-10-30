package net.minecraft.command;

import net.minecraft.util.text.ITextComponent;

public interface ICommandSender {
    // getName
    String func_70005_c_();

    // canUseCommand
    boolean func_70003_b(int nivel, String comando);

    // sendMessage
    void func_145747_a(ITextComponent texto);
}
