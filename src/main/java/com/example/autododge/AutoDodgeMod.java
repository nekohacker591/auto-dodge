package com.example.autododge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;

@Mod(AutoDodgeMod.MOD_ID)
public class AutoDodgeMod {
    public static final String MOD_ID = "autododge";

    public AutoDodgeMod() {
        MinecraftForge.EVENT_BUS.register(new AutoDodgeSystem());
    }
}
