package com.suipai.core.engine

object LookLibraryPrint {
    val muban = Look(
        id = "muban",
        name = "木版",
        summary = "木版。木纹暖，墨气发旧。",
        contrast = 0.14f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.10f,
        vignette = 0.12f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val huozi = Look(
        id = "huozi",
        name = "活字",
        summary = "活字。铅字冷，高光发硬。",
        contrast = 0.16f,
        saturation = -0.14f,
        temperature = -0.04f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val diaoban = Look(
        id = "diaoban",
        name = "雕版",
        summary = "雕版。刻痕冷，纸色发旧。",
        contrast = 0.14f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val taose = Look(
        id = "taose",
        name = "套色",
        summary = "套色。叠印暖，分色明显。",
        contrast = 0.12f,
        saturation = 0.04f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.08f,
        splitShadowBlue = 0.06f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val gonghua = Look(
        id = "gonghua",
        name = "拱花",
        summary = "拱花。凸纹白，高光发薄。",
        contrast = 0.06f,
        saturation = -0.12f,
        temperature = 0.04f,
        highlightRoll = 0.24f,
        shadowLift = 0.14f,
        grain = 0.04f,
        vignette = 0.05f,
        fade = 0.10f,
        defaultIntensity = 0.46f,
    )

    val douban = Look(
        id = "douban",
        name = "饾版",
        summary = "饾版。短版叠，水色发润。",
        contrast = 0.10f,
        saturation = 0.00f,
        temperature = 0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val taohuawu = Look(
        id = "taohuawu",
        name = "桃花坞",
        summary = "桃花坞。年画暖，饱和发粉。",
        contrast = 0.14f,
        saturation = 0.08f,
        temperature = 0.10f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val yangliuqing = Look(
        id = "yangliuqing",
        name = "杨柳青",
        summary = "杨柳青。粉脸暖，水色发柔。",
        contrast = 0.10f,
        saturation = 0.04f,
        temperature = 0.08f,
        highlightRoll = 0.18f,
        shadowLift = 0.10f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.08f,
        softness = 0.08f,
        skinProtect = 0.12f,
        defaultIntensity = 0.50f,
    )

    val zhuxian = Look(
        id = "zhuxian",
        name = "朱仙",
        summary = "朱仙镇。朱红硬，对比足。",
        contrast = 0.18f,
        saturation = 0.08f,
        temperature = 0.10f,
        highlightRoll = 0.10f,
        shadowLift = 0.02f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.02f,
        defaultIntensity = 0.56f,
    )

    val wuqiang = Look(
        id = "wuqiang",
        name = "武强",
        summary = "武强。墨线硬，色块发平。",
        contrast = 0.16f,
        saturation = 0.04f,
        temperature = 0.06f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val mianzhu = Look(
        id = "mianzhu",
        name = "绵竹",
        summary = "绵竹。年画暖，金线发箔。",
        contrast = 0.14f,
        saturation = 0.06f,
        temperature = 0.10f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val fengxiangz = Look(
        id = "fengxiangz",
        name = "凤翔",
        summary = "凤翔。泥塑暖，色块发硬。",
        contrast = 0.16f,
        saturation = 0.06f,
        temperature = 0.10f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val yangjiabu = Look(
        id = "yangjiabu",
        name = "杨家埠",
        summary = "杨家埠。木版暖，门神发红。",
        contrast = 0.16f,
        saturation = 0.08f,
        temperature = 0.10f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val foshan = Look(
        id = "foshan",
        name = "佛山",
        summary = "佛山。金漆暖，年画发亮。",
        contrast = 0.14f,
        saturation = 0.06f,
        temperature = 0.12f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.12f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shizhu = Look(
        id = "shizhu",
        name = "十竹",
        summary = "十竹斋。水色润，高光发薄。",
        contrast = 0.08f,
        saturation = -0.04f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val jiezi = Look(
        id = "jiezi",
        name = "芥子",
        summary = "芥子园。谱色淡，墨气发润。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.06f,
        vignette = 0.06f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val dianshi = Look(
        id = "dianshi",
        name = "点石",
        summary = "点石斋。石印冷，新闻发灰。",
        contrast = 0.12f,
        saturation = -0.14f,
        temperature = -0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val keluo = Look(
        id = "keluo",
        name = "珂罗",
        summary = "珂罗版。细网点，高光发薄。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = 0.02f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.06f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val shiyin = Look(
        id = "shiyin",
        name = "石印",
        summary = "石印。平版冷，墨色发灰。",
        contrast = 0.10f,
        saturation = -0.12f,
        temperature = 0.00f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.07f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val jiaoyin = Look(
        id = "jiaoyin",
        name = "胶印",
        summary = "胶印。网点平，高光干净。",
        contrast = 0.10f,
        saturation = -0.06f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val muke = Look(
        id = "muke",
        name = "木刻",
        summary = "木刻。刀痕硬，对比足。",
        contrast = 0.20f,
        saturation = -0.16f,
        temperature = -0.02f,
        highlightRoll = 0.08f,
        shadowLift = 0.00f,
        grain = 0.08f,
        vignette = 0.12f,
        fade = 0.02f,
        defaultIntensity = 0.56f,
    )

    val tongban = Look(
        id = "tongban",
        name = "铜版",
        summary = "铜版。细线冷，高光发硬。",
        contrast = 0.16f,
        saturation = -0.14f,
        temperature = -0.04f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val xinban = Look(
        id = "xinban",
        name = "锌版",
        summary = "锌版。灰白冷，高光发金属。",
        contrast = 0.14f,
        saturation = -0.16f,
        temperature = -0.06f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shiban = Look(
        id = "shiban",
        name = "石版",
        summary = "石版。石灰暖，颗粒中。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.10f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val siwang = Look(
        id = "siwang",
        name = "丝网",
        summary = "丝网。色块平，边缘发硬。",
        contrast = 0.14f,
        saturation = 0.04f,
        temperature = 0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.02f,
        defaultIntensity = 0.52f,
    )

    val louyin = Look(
        id = "louyin",
        name = "漏印",
        summary = "漏印。镂空暖，色层发哑。",
        contrast = 0.12f,
        saturation = 0.00f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        grain = 0.08f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qianyin = Look(
        id = "qianyin",
        name = "钤印",
        summary = "钤印。朱文暖，印泥发油。",
        contrast = 0.14f,
        saturation = 0.08f,
        temperature = 0.12f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.10f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val zhuwen = Look(
        id = "zhuwen",
        name = "朱文",
        summary = "朱文。红字暖，底白发薄。",
        contrast = 0.16f,
        saturation = 0.08f,
        temperature = 0.10f,
        highlightRoll = 0.12f,
        shadowLift = 0.08f,
        splitHighlightWarm = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val baiwen = Look(
        id = "baiwen",
        name = "白文",
        summary = "白文。阴刻白，朱底发沉。",
        contrast = 0.16f,
        saturation = 0.06f,
        temperature = 0.10f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val yinke = Look(
        id = "yinke",
        name = "阴刻",
        summary = "阴刻。凹线冷，阴影沉。",
        contrast = 0.16f,
        saturation = -0.12f,
        temperature = -0.02f,
        highlightRoll = 0.12f,
        shadowLift = 0.02f,
        grain = 0.07f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val yangke = Look(
        id = "yangke",
        name = "阳刻",
        summary = "阳刻。凸线暖，高光发硬。",
        contrast = 0.16f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.02f,
        defaultIntensity = 0.54f,
    )

    val jiandi = Look(
        id = "jiandi",
        name = "减地",
        summary = "减地。浅浮雕，高光发薄。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val tixi = Look(
        id = "tixi",
        name = "剔犀",
        summary = "剔犀。层漆暖，刀口发旧。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.07f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val qiangjin = Look(
        id = "qiangjin",
        name = "戗金",
        summary = "戗金。金丝嵌，漆底发沉。",
        contrast = 0.16f,
        saturation = 0.02f,
        temperature = 0.12f,
        highlightRoll = 0.12f,
        shadowLift = 0.02f,
        splitHighlightWarm = 0.12f,
        grain = 0.05f,
        vignette = 0.14f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val xieke = Look(
        id = "xieke",
        name = "写刻",
        summary = "写刻。手书暖，刀意发润。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val tihua = Look(
        id = "tihua",
        name = "题花",
        summary = "题花。栏花淡，纸色发旧。",
        contrast = 0.08f,
        saturation = -0.06f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.05f,
        vignette = 0.06f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val qianye = Look(
        id = "qianye",
        name = "签页",
        summary = "签页。书签暖，纸边发旧。",
        contrast = 0.08f,
        saturation = -0.08f,
        temperature = 0.06f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.06f,
        vignette = 0.06f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val shuying = Look(
        id = "shuying",
        name = "书影",
        summary = "书影。影印冷，纸灰发薄。",
        contrast = 0.10f,
        saturation = -0.14f,
        temperature = 0.02f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.07f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val cangban = Look(
        id = "cangban",
        name = "藏版",
        summary = "藏版。旧版暖，墨气发沉。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.12f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val yinzong = Look(
        id = "yinzong",
        name = "印宗",
        summary = "印宗。印谱冷，朱白对照。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        splitHighlightWarm = 0.08f,
        grain = 0.06f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val all: List<Look> = listOf(
        muban, huozi, diaoban, taose, gonghua, douban, taohuawu, yangliuqing, zhuxian, wuqiang,
        mianzhu, fengxiangz, yangjiabu, foshan, shizhu, jiezi, dianshi, keluo, shiyin, jiaoyin,
        muke, tongban, xinban, shiban, siwang, louyin, qianyin, zhuwen, baiwen, yinke,
        yangke, jiandi, tixi, qiangjin, xieke, tihua, qianye, shuying, cangban, yinzong,
    )
}
