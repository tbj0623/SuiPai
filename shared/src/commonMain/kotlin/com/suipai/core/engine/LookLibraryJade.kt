package com.suipai.core.engine

object LookLibraryJade {
    val feicui = Look(
        id = "feicui",
        name = "翡翠",
        summary = "翡翠。冷翠绿，高光发玻。",
        contrast = 0.12f,
        saturation = 0.04f,
        temperature = -0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.03f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val hetian = Look(
        id = "hetian",
        name = "和田",
        summary = "和田。油润暖白，高光发糯。",
        contrast = 0.06f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.24f,
        shadowLift = 0.14f,
        grain = 0.03f,
        vignette = 0.05f,
        fade = 0.10f,
        softness = 0.08f,
        defaultIntensity = 0.46f,
    )

    val xiuyan = Look(
        id = "xiuyan",
        name = "岫岩",
        summary = "岫岩。浅黄绿，高光发透。",
        contrast = 0.08f,
        saturation = -0.04f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val lantianyu = Look(
        id = "lantianyu",
        name = "蓝田",
        summary = "蓝田。浅青玉，高光发旧。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = -0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val ziliao = Look(
        id = "ziliao",
        name = "籽料",
        summary = "籽料。皮色暖，肉质发糯。",
        contrast = 0.10f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val shanliao = Look(
        id = "shanliao",
        name = "山料",
        summary = "山料。石性冷，高光发干。",
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

    val bingzhong = Look(
        id = "bingzhong",
        name = "冰种",
        summary = "冰种。冷透白，高光发玻。",
        contrast = 0.06f,
        saturation = -0.12f,
        temperature = -0.06f,
        highlightRoll = 0.26f,
        shadowLift = 0.16f,
        grain = 0.02f,
        vignette = 0.04f,
        fade = 0.10f,
        defaultIntensity = 0.44f,
    )

    val bolizhong = Look(
        id = "bolizhong",
        name = "玻璃种",
        summary = "玻璃种。极透冷，高光发硬。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = -0.08f,
        highlightRoll = 0.24f,
        shadowLift = 0.14f,
        grain = 0.02f,
        vignette = 0.04f,
        fade = 0.06f,
        defaultIntensity = 0.46f,
    )

    val nuozhong = Look(
        id = "nuozhong",
        name = "糯种",
        summary = "糯种。雾白暖，高光发糯。",
        contrast = 0.04f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.24f,
        shadowLift = 0.16f,
        grain = 0.04f,
        vignette = 0.05f,
        fade = 0.12f,
        softness = 0.08f,
        defaultIntensity = 0.46f,
    )

    val douzhong = Look(
        id = "douzhong",
        name = "豆种",
        summary = "豆种。豆青闷，颗粒中。",
        contrast = 0.10f,
        saturation = -0.06f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.08f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val baiyu = Look(
        id = "baiyu",
        name = "白玉",
        summary = "白玉。暖白润，高光发柔。",
        contrast = 0.04f,
        saturation = -0.12f,
        temperature = 0.06f,
        highlightRoll = 0.26f,
        shadowLift = 0.16f,
        grain = 0.03f,
        vignette = 0.04f,
        fade = 0.12f,
        softness = 0.10f,
        defaultIntensity = 0.44f,
    )

    val biyu = Look(
        id = "biyu",
        name = "碧玉",
        summary = "碧玉。深冷绿，阴影沉。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = -0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val huangyu = Look(
        id = "huangyu",
        name = "黄玉",
        summary = "黄玉。暖黄润，高光发油。",
        contrast = 0.10f,
        saturation = 0.02f,
        temperature = 0.12f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        splitHighlightWarm = 0.08f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val tangyu = Look(
        id = "tangyu",
        name = "糖玉",
        summary = "糖玉。糖红暖，皮色发旧。",
        contrast = 0.12f,
        saturation = 0.04f,
        temperature = 0.12f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val cuiyu = Look(
        id = "cuiyu",
        name = "翠玉",
        summary = "翠玉。鲜绿冷，高光发透。",
        contrast = 0.12f,
        saturation = 0.06f,
        temperature = -0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        grain = 0.03f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val jinsiyu = Look(
        id = "jinsiyu",
        name = "金丝玉",
        summary = "金丝玉。金纹暖，高光发干。",
        contrast = 0.12f,
        saturation = 0.02f,
        temperature = 0.12f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val nanhong = Look(
        id = "nanhong",
        name = "南红",
        summary = "南红。柿红暖，高光发油。",
        contrast = 0.14f,
        saturation = 0.08f,
        temperature = 0.14f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.12f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val piaohua = Look(
        id = "piaohua",
        name = "飘花",
        summary = "飘花。绿丝冷，底子发透。",
        contrast = 0.08f,
        saturation = 0.00f,
        temperature = -0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val shuicao = Look(
        id = "shuicao",
        name = "水草",
        summary = "水草。藻纹冷，高光发透。",
        contrast = 0.10f,
        saturation = -0.04f,
        temperature = -0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val xuepo = Look(
        id = "xuepo",
        name = "血珀",
        summary = "血珀。深红暖，高光发透。",
        contrast = 0.16f,
        saturation = 0.06f,
        temperature = 0.12f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.10f,
        grain = 0.04f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val lanpo = Look(
        id = "lanpo",
        name = "蓝珀",
        summary = "蓝珀。冷蓝透，高光发玻。",
        contrast = 0.10f,
        saturation = -0.04f,
        temperature = -0.12f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        splitShadowBlue = 0.12f,
        grain = 0.03f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val jinpo = Look(
        id = "jinpo",
        name = "金珀",
        summary = "金珀。金黄透，高光发亮。",
        contrast = 0.12f,
        saturation = 0.06f,
        temperature = 0.16f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.12f,
        grain = 0.03f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val chongpo = Look(
        id = "chongpo",
        name = "虫珀",
        summary = "虫珀。琥珀暖，内含发旧。",
        contrast = 0.12f,
        saturation = 0.02f,
        temperature = 0.12f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.08f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val genpo = Look(
        id = "genpo",
        name = "根珀",
        summary = "根珀。木纹暖，高光发哑。",
        contrast = 0.14f,
        saturation = -0.02f,
        temperature = 0.10f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.08f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val kunlun = Look(
        id = "kunlun",
        name = "昆仑",
        summary = "昆仑。山玉冷，高光发干。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = -0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.12f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qilian = Look(
        id = "qilian",
        name = "祁连",
        summary = "祁连。翠青冷，山气发薄。",
        contrast = 0.10f,
        saturation = -0.04f,
        temperature = -0.06f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val dushan = Look(
        id = "dushan",
        name = "独山",
        summary = "独山。杂色玉，高光发旧。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val huanglongyu = Look(
        id = "huanglongyu",
        name = "黄龙玉",
        summary = "黄龙玉。暖黄透，高光发油。",
        contrast = 0.12f,
        saturation = 0.06f,
        temperature = 0.14f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val gebiyu = Look(
        id = "gebiyu",
        name = "戈壁玉",
        summary = "戈壁玉。风砺暖，高光发干。",
        contrast = 0.14f,
        saturation = -0.06f,
        temperature = 0.10f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.10f,
        vignette = 0.12f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val bingpiao = Look(
        id = "bingpiao",
        name = "冰飘",
        summary = "冰飘。底冰绿飘，高光发玻。",
        contrast = 0.08f,
        saturation = -0.04f,
        temperature = -0.06f,
        highlightRoll = 0.24f,
        shadowLift = 0.12f,
        grain = 0.03f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val kongqueshi = Look(
        id = "kongqueshi",
        name = "孔雀石",
        summary = "孔雀石。铜绿纹，高光发油。",
        contrast = 0.14f,
        saturation = 0.02f,
        temperature = -0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val yingshi = Look(
        id = "yingshi",
        name = "萤石",
        summary = "萤石。冷透彩，高光发玻。",
        contrast = 0.10f,
        saturation = 0.00f,
        temperature = -0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.03f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val shuijing = Look(
        id = "shuijing",
        name = "水晶",
        summary = "水晶。冷白透，高光发硬。",
        contrast = 0.08f,
        saturation = -0.14f,
        temperature = -0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.02f,
        vignette = 0.04f,
        fade = 0.06f,
        defaultIntensity = 0.46f,
    )

    val huangjing = Look(
        id = "huangjing",
        name = "黄晶",
        summary = "黄晶。暖黄透，高光发亮。",
        contrast = 0.10f,
        saturation = 0.04f,
        temperature = 0.12f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        splitHighlightWarm = 0.08f,
        grain = 0.03f,
        vignette = 0.06f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val fenjing = Look(
        id = "fenjing",
        name = "粉晶",
        summary = "粉晶。浅粉暖，高光发柔。",
        contrast = 0.06f,
        saturation = 0.02f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.14f,
        grain = 0.03f,
        vignette = 0.05f,
        fade = 0.10f,
        softness = 0.08f,
        defaultIntensity = 0.46f,
    )

    val baishuijing = Look(
        id = "baishuijing",
        name = "白晶",
        summary = "白晶。冷白透，高光发薄。",
        contrast = 0.06f,
        saturation = -0.16f,
        temperature = -0.04f,
        highlightRoll = 0.24f,
        shadowLift = 0.14f,
        grain = 0.02f,
        vignette = 0.04f,
        fade = 0.08f,
        defaultIntensity = 0.44f,
    )

    val jinfa = Look(
        id = "jinfa",
        name = "金发晶",
        summary = "金发晶。金针暖，底子发透。",
        contrast = 0.10f,
        saturation = 0.00f,
        temperature = 0.10f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        splitHighlightWarm = 0.10f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val youling = Look(
        id = "youling",
        name = "幽灵晶",
        summary = "幽灵晶。内影冷，高光发雾。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = -0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val furongzhong = Look(
        id = "furongzhong",
        name = "芙蓉种",
        summary = "芙蓉种。粉绿暖，高光发糯。",
        contrast = 0.08f,
        saturation = 0.02f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        softness = 0.08f,
        defaultIntensity = 0.48f,
    )

    val all: List<Look> = listOf(
        feicui, hetian, xiuyan, lantianyu, ziliao, shanliao, bingzhong, bolizhong, nuozhong, douzhong,
        baiyu, biyu, huangyu, tangyu, cuiyu, jinsiyu, nanhong, piaohua, shuicao, xuepo,
        lanpo, jinpo, chongpo, genpo, kunlun, qilian, dushan, huanglongyu, gebiyu, bingpiao,
        kongqueshi, yingshi, shuijing, huangjing, fenjing, baishuijing, jinfa, youling, furongzhong,
    )
}
