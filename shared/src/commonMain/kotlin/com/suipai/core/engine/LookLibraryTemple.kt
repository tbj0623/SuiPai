package com.suipai.core.engine

object LookLibraryTemple {
    val shanmen = Look(
        id = "shanmen",
        name = "山门",
        summary = "山门。门墙冷灰，对比中，高光发干。",
        contrast = 0.14f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.08f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val daxiong = Look(
        id = "daxiong",
        name = "大雄",
        summary = "大雄。殿金暖，暗角沉，高光发箔。",
        contrast = 0.14f,
        saturation = 0.04f,
        temperature = 0.12f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.14f,
        grain = 0.05f,
        vignette = 0.14f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val zhonglou = Look(
        id = "zhonglou",
        name = "钟楼",
        summary = "钟楼。楼木暖，钟铜冷，高光发干。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.05f,
        defaultIntensity = 0.50f,
    )

    val gulou = Look(
        id = "gulou",
        name = "鼓楼",
        summary = "鼓楼。鼓皮暖，楼影沉，颗粒中。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.08f,
        grain = 0.10f,
        vignette = 0.10f,
        fade = 0.05f,
        defaultIntensity = 0.52f,
    )

    val jingtang = Look(
        id = "jingtang",
        name = "经堂",
        summary = "经堂。经纸微暖，高光发薄，阴影匀。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val chanfang = Look(
        id = "chanfang",
        name = "禅房",
        summary = "禅房。房冷灰，饱和低，高光发旧。",
        contrast = 0.10f,
        saturation = -0.16f,
        temperature = -0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val xiangji = Look(
        id = "xiangji",
        name = "香积",
        summary = "香积。厨烟暖灰，颗粒重，高光发旧。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.08f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.12f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val jietan = Look(
        id = "jietan",
        name = "戒坛",
        summary = "戒坛。坛石冷，对比中，高光发硬。",
        contrast = 0.14f,
        saturation = -0.14f,
        temperature = -0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val luohantang = Look(
        id = "luohantang",
        name = "罗汉堂",
        summary = "罗汉堂。金身暖，殿影沉，高光发油。",
        contrast = 0.12f,
        saturation = 0.02f,
        temperature = 0.10f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.12f,
        grain = 0.06f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val cangjing = Look(
        id = "cangjing",
        name = "藏经",
        summary = "藏经。经卷微暖，高光发干，阴影匀。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val tayuan = Look(
        id = "tayuan",
        name = "塔院",
        summary = "塔院。塔影冷，院草浅，高光发薄。",
        contrast = 0.10f,
        saturation = -0.12f,
        temperature = -0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        splitShadowBlue = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.48f,
    )

    val senglang = Look(
        id = "senglang",
        name = "僧廊",
        summary = "僧廊。廊木微暖，高光发干，暗角轻。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.05f,
        defaultIntensity = 0.50f,
    )

    val zhaitang = Look(
        id = "zhaitang",
        name = "斋堂",
        summary = "斋堂。斋桌浅暖，高光发干，颗粒轻。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val ketang = Look(
        id = "ketang",
        name = "客堂",
        summary = "客堂。客坐微暖，软，高光发薄。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        softness = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val fangzhang = Look(
        id = "fangzhang",
        name = "方丈",
        summary = "方丈。室冷灰，饱和低，高光发旧。",
        contrast = 0.10f,
        saturation = -0.16f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qielan = Look(
        id = "qielan",
        name = "伽蓝",
        summary = "伽蓝。院落冷，高光发薄，暗角沉。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = -0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.12f,
        fade = 0.05f,
        defaultIntensity = 0.52f,
    )

    val fodian = Look(
        id = "fodian",
        name = "佛殿",
        summary = "佛殿。殿金暖，烛晖发油，暗角沉。",
        contrast = 0.14f,
        saturation = 0.04f,
        temperature = 0.12f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.14f,
        grain = 0.06f,
        vignette = 0.16f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val putuan = Look(
        id = "putuan",
        name = "蒲团",
        summary = "蒲团。蒲草暖灰，颗粒重，高光发干。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.12f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qingsheng = Look(
        id = "qingsheng",
        name = "磬声",
        summary = "磬声。铜冷，声影薄，高光发硬。",
        contrast = 0.12f,
        saturation = -0.16f,
        temperature = -0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        splitShadowBlue = 0.08f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.05f,
        defaultIntensity = 0.50f,
    )

    val jingfan = Look(
        id = "jingfan",
        name = "经幡",
        summary = "经幡。幡布微暖，高光发薄，风色发旧。",
        contrast = 0.08f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.06f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val zhuanjing = Look(
        id = "zhuanjing",
        name = "转经",
        summary = "转经。筒铜暖，对比中，高光发油。",
        contrast = 0.12f,
        saturation = 0.02f,
        temperature = 0.10f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val xiangdao = Look(
        id = "xiangdao",
        name = "香道",
        summary = "香道。香路微暖，烟霭发薄，暗角轻。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        softness = 0.06f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val chandeng = Look(
        id = "chandeng",
        name = "禅灯",
        summary = "禅灯。灯暖，室沉，高光发晖。",
        contrast = 0.14f,
        saturation = -0.06f,
        temperature = 0.10f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.12f,
        grain = 0.08f,
        vignette = 0.16f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val jinshen = Look(
        id = "jinshen",
        name = "金身",
        summary = "金身。贴金暖箔，高光发油，阴影暖。",
        contrast = 0.14f,
        saturation = 0.06f,
        temperature = 0.16f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.16f,
        grain = 0.04f,
        vignette = 0.10f,
        fade = 0.03f,
        defaultIntensity = 0.54f,
    )

    val bihua = Look(
        id = "bihua",
        name = "壁画",
        summary = "壁画。壁色发旧，微暖，颗粒中。",
        contrast = 0.10f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.10f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val gongzhuo = Look(
        id = "gongzhuo",
        name = "供桌",
        summary = "供桌。漆木暖，高光发油，暗角轻。",
        contrast = 0.12f,
        saturation = 0.02f,
        temperature = 0.10f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val fanzhong = Look(
        id = "fanzhong",
        name = "梵钟",
        summary = "梵钟。钟铜冷，高光发硬，对比中。",
        contrast = 0.14f,
        saturation = -0.14f,
        temperature = -0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val lianzuo = Look(
        id = "lianzuo",
        name = "莲座",
        summary = "莲座。莲瓣微暖，软，高光发糯。",
        contrast = 0.06f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.24f,
        shadowLift = 0.14f,
        softness = 0.08f,
        grain = 0.03f,
        vignette = 0.05f,
        fade = 0.10f,
        defaultIntensity = 0.46f,
    )

    val all: List<Look> = listOf(
        shanmen, daxiong, zhonglou, gulou, jingtang, chanfang, xiangji, jietan,
        luohantang, cangjing, tayuan, senglang, zhaitang, ketang, fangzhang, qielan,
        fodian, putuan, qingsheng, jingfan, zhuanjing, xiangdao, chandeng, jinshen,
        bihua, gongzhuo, fanzhong, lianzuo,
    )
}
