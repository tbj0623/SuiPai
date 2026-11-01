package com.suipai.core.engine

object LookLibraryPaint {
    val gongbi = Look(
        id = "gongbi",
        name = "工笔",
        summary = "工笔。细线冷，设色发匀。",
        contrast = 0.10f,
        saturation = 0.02f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val xieyi = Look(
        id = "xieyi",
        name = "写意",
        summary = "写意。泼墨暖，笔意发畅。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val baimiao = Look(
        id = "baimiao",
        name = "白描",
        summary = "白描。单线冷，纸白发薄。",
        contrast = 0.12f,
        saturation = -0.16f,
        temperature = 0.00f,
        highlightRoll = 0.16f,
        shadowLift = 0.10f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val mogu = Look(
        id = "mogu",
        name = "没骨",
        summary = "没骨。无勾暖，色晕发润。",
        contrast = 0.08f,
        saturation = 0.02f,
        temperature = 0.06f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.10f,
        softness = 0.08f,
        defaultIntensity = 0.48f,
    )

    val qinglv = Look(
        id = "qinglv",
        name = "青绿",
        summary = "青绿。石色冷，山青发亮。",
        contrast = 0.12f,
        saturation = 0.04f,
        temperature = -0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val jinbi = Look(
        id = "jinbi",
        name = "金碧",
        summary = "金碧。金线暖，青绿发箔。",
        contrast = 0.14f,
        saturation = 0.04f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.10f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val jiehua = Look(
        id = "jiehua",
        name = "界画",
        summary = "界画。直尺冷，宫室发稳。",
        contrast = 0.14f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shanshui = Look(
        id = "shanshui",
        name = "山水",
        summary = "山水。远山冷，水气发薄。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = -0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val huaniao = Look(
        id = "huaniao",
        name = "花鸟",
        summary = "花鸟。折枝暖，翎毛发细。",
        contrast = 0.10f,
        saturation = 0.04f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val renwu = Look(
        id = "renwu",
        name = "人物",
        summary = "人物。衣纹暖，肤色受护。",
        contrast = 0.10f,
        saturation = -0.02f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        softness = 0.06f,
        skinProtect = 0.12f,
        defaultIntensity = 0.50f,
    )

    val zoushou = Look(
        id = "zoushou",
        name = "走兽",
        summary = "走兽。皮毛暖，骨力发沉。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = 0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val anma = Look(
        id = "anma",
        name = "鞍马",
        summary = "鞍马。蹄尘暖，鞍色发旧。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shinu = Look(
        id = "shinu",
        name = "仕女",
        summary = "仕女。粉面暖，衣带发柔。",
        contrast = 0.08f,
        saturation = 0.02f,
        temperature = 0.08f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        softness = 0.08f,
        skinProtect = 0.16f,
        defaultIntensity = 0.48f,
    )

    val daoshi = Look(
        id = "daoshi",
        name = "道释",
        summary = "道释。佛光冷，衣纹发静。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val sijun = Look(
        id = "sijun",
        name = "四君",
        summary = "四君子。梅兰竹菊，墨气发清。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val suihan = Look(
        id = "suihan",
        name = "岁寒",
        summary = "岁寒。松竹梅，冷意发清。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = -0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val diantai = Look(
        id = "diantai",
        name = "点苔",
        summary = "点苔。墨点冷，石根发沉。",
        contrast = 0.14f,
        saturation = -0.12f,
        temperature = -0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val cunfa = Look(
        id = "cunfa",
        name = "皴法",
        summary = "皴法。石纹冷，笔触发干。",
        contrast = 0.14f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.09f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val fupi = Look(
        id = "fupi",
        name = "斧劈",
        summary = "斧劈。大斧冷，石面发硬。",
        contrast = 0.18f,
        saturation = -0.12f,
        temperature = 0.00f,
        highlightRoll = 0.10f,
        shadowLift = 0.02f,
        grain = 0.07f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val pima = Look(
        id = "pima",
        name = "披麻",
        summary = "披麻。长皴暖，土坡发润。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.07f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val jiesuo = Look(
        id = "jiesuo",
        name = "解索",
        summary = "解索。曲皴冷，石脉发乱。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val zhedai = Look(
        id = "zhedai",
        name = "折带",
        summary = "折带。横折冷，层岩发硬。",
        contrast = 0.14f,
        saturation = -0.10f,
        temperature = 0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val yudian = Look(
        id = "yudian",
        name = "雨点",
        summary = "雨点。短点冷，山雨发密。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = -0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.10f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val midian = Look(
        id = "midian",
        name = "米点",
        summary = "米点。横点暖，云山发润。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.08f,
        vignette = 0.06f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val juanyun = Look(
        id = "juanyun",
        name = "卷云",
        summary = "卷云。云皴冷，高光发薄。",
        contrast = 0.08f,
        saturation = -0.12f,
        temperature = -0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.10f,
        defaultIntensity = 0.46f,
    )

    val niumao = Look(
        id = "niumao",
        name = "牛毛",
        summary = "牛毛。细皴暖，山肌发密。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.09f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val luanchai = Look(
        id = "luanchai",
        name = "乱柴",
        summary = "乱柴。枯笔冷，枝乱发干。",
        contrast = 0.14f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.10f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val tuoni = Look(
        id = "tuoni",
        name = "拖泥",
        summary = "拖泥。湿墨暖，水痕发旧。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val pomoink = Look(
        id = "pomoink",
        name = "破墨",
        summary = "破墨。浓淡破，水气发润。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.10f,
        defaultIntensity = 0.50f,
    )

    val hongtuo = Look(
        id = "hongtuo",
        name = "烘托",
        summary = "烘托。烘染暖，云气发薄。",
        contrast = 0.06f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.12f,
        softness = 0.08f,
        defaultIntensity = 0.46f,
    )

    val liubai = Look(
        id = "liubai",
        name = "留白",
        summary = "留白。空水冷，高光发薄。",
        contrast = 0.04f,
        saturation = -0.16f,
        temperature = -0.02f,
        highlightRoll = 0.26f,
        shadowLift = 0.16f,
        grain = 0.03f,
        vignette = 0.04f,
        fade = 0.12f,
        defaultIntensity = 0.44f,
    )

    val tikuan = Look(
        id = "tikuan",
        name = "题款",
        summary = "题款。边字冷，印色发朱。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val zhuangbiao = Look(
        id = "zhuangbiao",
        name = "装裱",
        summary = "装裱。绫边暖，画心发平。",
        contrast = 0.08f,
        saturation = -0.06f,
        temperature = 0.06f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val zhoutou = Look(
        id = "zhoutou",
        name = "轴头",
        summary = "轴头。玉木暖，卷气发旧。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val jingyan = Look(
        id = "jingyan",
        name = "惊燕",
        summary = "惊燕。裱带冷，垂影发细。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.48f,
    )

    val baoshou = Look(
        id = "baoshou",
        name = "包首",
        summary = "包首。锦包暖，卷外发旧。",
        contrast = 0.10f,
        saturation = 0.00f,
        temperature = 0.08f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val tiangan = Look(
        id = "tiangan",
        name = "天杆",
        summary = "天杆。上轴冷，木色发直。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val digan = Look(
        id = "digan",
        name = "地杆",
        summary = "地杆。下轴暖，坠力发沉。",
        contrast = 0.14f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val qiantiao = Look(
        id = "qiantiao",
        name = "签条",
        summary = "签条。窄纸暖，题名发细。",
        contrast = 0.10f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val hecun = Look(
        id = "hecun",
        name = "荷皴",
        summary = "荷皴。叶脉冷，石纹发皱。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.07f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val all: List<Look> = listOf(
        gongbi, xieyi, baimiao, mogu, qinglv, jinbi, jiehua, shanshui, huaniao, renwu,
        zoushou, anma, shinu, daoshi, sijun, suihan, diantai, cunfa, fupi, pima,
        jiesuo, zhedai, yudian, midian, juanyun, niumao, luanchai, tuoni, pomoink, hongtuo,
        liubai, tikuan, zhuangbiao, zhoutou, jingyan, baoshou, tiangan, digan, qiantiao, hecun,
    )
}
