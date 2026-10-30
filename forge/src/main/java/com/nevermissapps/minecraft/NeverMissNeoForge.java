package com.nevermissapps.minecraft;

import net.neoforged.fml.common.Mod;

import java.io.File;

/** A entrada do NeoForge (1.20.4 ao 26.x). O Forge não olha esta anotação: ele entra pelo {@link NeverMissForge}. */
@Mod("nevermiss")
public final class NeverMissNeoForge {
    public NeverMissNeoForge() {
        Forge.criar("neoforge", "net.neoforged.neoforge.common.NeoForge", "net.neoforged.neoforge.event.", new File("config", "nevermiss"));
    }
}
