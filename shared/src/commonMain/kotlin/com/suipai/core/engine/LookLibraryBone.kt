package com.suipai.core.engine

object LookLibraryBone {
    val niugupian = Look(
        id = "niugupian", name = "牛骨片", summary = "牛骨片。片厚白，油光发暖，高光发哑。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.08f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val yanggupian = Look(
        id = "yanggupian", name = "羊骨片", summary = "羊骨片。片较薄，脂白发纸，高光发软。",
        contrast = 0.12f, saturation = -0.08f, temperature = 0.06f,
        highlightRoll = 0.18f, shadowLift = 0.08f, grain = 0.06f,
        vignette = 0.08f, fade = 0.06f, softness = 0.04f, defaultIntensity = 0.52f,
    )
    val zhugupian = Look(
        id = "zhugupian", name = "猪骨片", summary = "猪骨片。松质多，粉白发灰，高光发干。",
        contrast = 0.12f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val lugupian = Look(
        id = "lugupian", name = "鹿骨片", summary = "鹿骨片。骨密实，暖褐发干，高光发硬。",
        contrast = 0.16f, saturation = 0.00f, temperature = 0.10f,
        highlightRoll = 0.14f, shadowLift = 0.04f, splitHighlightWarm = 0.08f,
        grain = 0.06f, vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val yuguci = Look(
        id = "yuguci", name = "鱼骨刺", summary = "鱼骨刺。刺细亮，冷白发硬，高光发刺。",
        contrast = 0.16f, saturation = -0.10f, temperature = -0.02f,
        highlightRoll = 0.12f, shadowLift = 0.04f, splitShadowBlue = 0.06f,
        grain = 0.05f, vignette = 0.10f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val niaoguguan = Look(
        id = "niaoguguan", name = "鸟骨管", summary = "鸟骨管。管中空，薄壁发纸，高光发滑。",
        contrast = 0.10f, saturation = -0.08f, temperature = 0.04f,
        highlightRoll = 0.20f, shadowLift = 0.10f, grain = 0.04f,
        vignette = 0.08f, fade = 0.08f, softness = 0.06f, defaultIntensity = 0.50f,
    )
    val guzhenmian = Look(
        id = "guzhenmian", name = "骨针", summary = "骨针。针尖亮，杆白发干，高光发硬。",
        contrast = 0.16f, saturation = -0.08f, temperature = 0.04f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.10f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val guzhuimian = Look(
        id = "guzhuimian", name = "骨锥", summary = "骨锥。锥身粗，尖端发亮，高光发刺。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.06f,
        vignette = 0.12f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val guzanmian = Look(
        id = "guzanmian", name = "骨簪", summary = "骨簪。簪身圆，油光发暖，高光发滑。",
        contrast = 0.12f, saturation = -0.04f, temperature = 0.08f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.08f,
        grain = 0.04f, vignette = 0.08f, fade = 0.04f, defaultIntensity = 0.52f,
    )
    val gushuchimian = Look(
        id = "gushuchimian", name = "骨梳齿", summary = "骨梳齿。齿密齐，缝影发暗，高光发干。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.06f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gukoumian = Look(
        id = "gukoumian", name = "骨扣", summary = "骨扣。扣面圆，孔影发暗，高光发点。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.05f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val guzhumian = Look(
        id = "guzhumian", name = "骨珠", summary = "骨珠。珠圆白，一点发滑，高光发软。",
        contrast = 0.12f, saturation = -0.08f, temperature = 0.06f,
        highlightRoll = 0.18f, shadowLift = 0.08f, grain = 0.04f,
        vignette = 0.08f, fade = 0.06f, softness = 0.04f, defaultIntensity = 0.52f,
    )
    val guguanmian = Look(
        id = "guguanmian", name = "骨管", summary = "骨管。管壁厚，内孔发暗，高光发哑。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.06f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gushaomian = Look(
        id = "gushaomian", name = "骨哨", summary = "骨哨。哨口小，骨色发旧，高光发干。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.08f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gudaobingmian = Look(
        id = "gudaobingmian", name = "骨刀柄", summary = "骨刀柄。柄握旧，油渍发暖，高光发黏。",
        contrast = 0.14f, saturation = 0.00f, temperature = 0.10f,
        highlightRoll = 0.16f, shadowLift = 0.06f, splitHighlightWarm = 0.10f,
        grain = 0.07f, vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val guxiangmian = Look(
        id = "guxiangmian", name = "骨镶", summary = "骨镶。镶片平，接缝发暗，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val gudiaomian = Look(
        id = "gudiaomian", name = "骨雕面", summary = "骨雕面。刀痕凹，花纹发暖，高光发软。",
        contrast = 0.14f, saturation = 0.00f, temperature = 0.08f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.06f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val guhuibaimian = Look(
        id = "guhuibaimian", name = "骨灰白", summary = "骨灰白。煅白灰，粉面发纸，高光发薄。",
        contrast = 0.08f, saturation = -0.12f, temperature = 0.04f,
        highlightRoll = 0.22f, shadowLift = 0.12f, grain = 0.06f,
        vignette = 0.06f, fade = 0.10f, softness = 0.06f, defaultIntensity = 0.48f,
    )
    val guhuangmian = Look(
        id = "guhuangmian", name = "骨黄", summary = "骨黄。陈黄匀，脂光发暖，高光发哑。",
        contrast = 0.12f, saturation = 0.04f, temperature = 0.12f,
        highlightRoll = 0.16f, shadowLift = 0.06f, splitHighlightWarm = 0.10f,
        grain = 0.06f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val guyoumian = Look(
        id = "guyoumian", name = "骨油", summary = "骨油。油浸透，表面发黏，高光发软。",
        contrast = 0.10f, saturation = 0.02f, temperature = 0.12f,
        highlightRoll = 0.20f, shadowLift = 0.10f, splitHighlightWarm = 0.12f,
        grain = 0.05f, vignette = 0.08f, fade = 0.08f, softness = 0.04f, defaultIntensity = 0.50f,
    )
    val guwenmian = Look(
        id = "guwenmian", name = "骨纹", summary = "骨纹。哈氏纹，细线发浅，高光发纸。",
        contrast = 0.12f, saturation = -0.08f, temperature = 0.06f,
        highlightRoll = 0.18f, shadowLift = 0.08f, grain = 0.05f,
        vignette = 0.08f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val gukongmian = Look(
        id = "gukongmian", name = "骨孔", summary = "骨孔。孔眼疏，阴影发点，高光发干。",
        contrast = 0.14f, saturation = -0.08f, temperature = 0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gufengmian = Look(
        id = "gufengmian", name = "骨缝", summary = "骨缝。缝线细，接口发暗，高光发哑。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.06f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gumomian = Look(
        id = "gumomian", name = "骨膜", summary = "骨膜。膜薄透，湿光发软，高光发纸。",
        contrast = 0.08f, saturation = -0.06f, temperature = 0.04f,
        highlightRoll = 0.22f, shadowLift = 0.12f, grain = 0.04f,
        vignette = 0.06f, fade = 0.10f, softness = 0.08f, defaultIntensity = 0.48f,
    )
    val gusuimian = Look(
        id = "gusuimian", name = "骨髓", summary = "骨髓。髓心黄，油光发闷，高光发黏。",
        contrast = 0.12f, saturation = 0.04f, temperature = 0.12f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.10f,
        grain = 0.06f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val gufenmian = Look(
        id = "gufenmian", name = "骨粉", summary = "骨粉。粉粒细，白灰发干，高光发纸。",
        contrast = 0.10f, saturation = -0.10f, temperature = 0.04f,
        highlightRoll = 0.20f, shadowLift = 0.10f, grain = 0.08f,
        vignette = 0.08f, fade = 0.08f, defaultIntensity = 0.50f,
    )
    val duangumian = Look(
        id = "duangumian", name = "煅骨", summary = "煅骨。煅白脆，灰边发冷，高光发硬。",
        contrast = 0.14f, saturation = -0.12f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val shenggumian = Look(
        id = "shenggumian", name = "生骨", summary = "生骨。骨新白，湿光发软，高光发滑。",
        contrast = 0.10f, saturation = -0.06f, temperature = 0.04f,
        highlightRoll = 0.20f, shadowLift = 0.10f, grain = 0.04f,
        vignette = 0.08f, fade = 0.08f, softness = 0.06f, defaultIntensity = 0.50f,
    )
    val chengumian = Look(
        id = "chengumian", name = "陈骨", summary = "陈骨。骨色旧，裂痕发干，高光发哑。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.08f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.12f, fade = 0.06f, defaultIntensity = 0.54f,
    )
    val huashigumian = Look(
        id = "huashigumian", name = "化石骨", summary = "化石骨。石化硬，矿色发沉，高光发干。",
        contrast = 0.16f, saturation = -0.08f, temperature = 0.06f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.07f,
        vignette = 0.14f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val guijiapian = Look(
        id = "guijiapian", name = "龟甲片", summary = "龟甲片。甲纹裂，褐边发暖，高光发硬。",
        contrast = 0.16f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.14f, shadowLift = 0.04f, splitHighlightWarm = 0.08f,
        grain = 0.07f, vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val jiaozhipian = Look(
        id = "jiaozhipian", name = "角质片", summary = "角质片。角半透，暖黄发黏，高光发滑。",
        contrast = 0.12f, saturation = 0.04f, temperature = 0.12f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.10f,
        grain = 0.05f, vignette = 0.08f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val tikemian = Look(
        id = "tikemian", name = "蹄壳", summary = "蹄壳。壳壁厚，角纹发干，高光发哑。",
        contrast = 0.14f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val gubaicimian = Look(
        id = "gubaicimian", name = "骨白瓷", summary = "骨白瓷。瓷白净，骨粉发薄，高光发纸。",
        contrast = 0.08f, saturation = -0.10f, temperature = 0.04f,
        highlightRoll = 0.22f, shadowLift = 0.12f, grain = 0.03f,
        vignette = 0.06f, fade = 0.10f, softness = 0.06f, defaultIntensity = 0.48f,
    )
    val gupaomian = Look(
        id = "gupaomian", name = "骨抛", summary = "骨抛。抛光亮，镜面发滑，高光发刺。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.02f,
        vignette = 0.08f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val guqiemian = Look(
        id = "guqiemian", name = "骨切", summary = "骨切。切面平，刀口发亮，高光发硬。",
        contrast = 0.16f, saturation = -0.08f, temperature = 0.04f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.04f,
        vignette = 0.10f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val guxinmian = Look(
        id = "guxinmian", name = "骨芯", summary = "骨芯。芯松质，孔多发灰，高光发哑。",
        contrast = 0.12f, saturation = -0.08f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val gupimian = Look(
        id = "gupimian", name = "骨皮", summary = "骨皮。皮层糙，风化发灰，高光发干。",
        contrast = 0.12f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.09f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val gujinmian = Look(
        id = "gujinmian", name = "骨筋", summary = "骨筋。筋纹凸，阴影发细，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.06f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.06f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val guyamian = Look(
        id = "guyamian", name = "骨牙面", summary = "骨牙面。牙釉亮，冷白发硬，高光发刺。",
        contrast = 0.16f, saturation = -0.10f, temperature = 0.04f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.03f,
        vignette = 0.10f, fade = 0.02f, defaultIntensity = 0.56f,
    )

    val all: List<Look> = listOf(
        niugupian, yanggupian, zhugupian, lugupian, yuguci, niaoguguan, guzhenmian, guzhuimian, guzanmian, gushuchimian,
        gukoumian, guzhumian, guguanmian, gushaomian, gudaobingmian, guxiangmian, gudiaomian, guhuibaimian,  guhuangmian, guyoumian,
        guwenmian, gukongmian, gufengmian, gumomian, gusuimian, gufenmian, duangumian, shenggumian, chengumian, huashigumian,
        guijiapian, jiaozhipian, tikemian, gubaicimian, gupaomian,  guqiemian, guxinmian, gupimian, gujinmian, guyamian,
    )
}
