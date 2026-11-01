package com.suipai.core.engine

object LookLibraryGame {
    val weiqi = Look(
        id = "weiqi",
        name = "围棋",
        summary = "围棋。黑白对照，盘色发旧。",
        contrast = 0.16f,
        saturation = -0.16f,
        temperature = 0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val xiangqi = Look(
        id = "xiangqi",
        name = "象棋",
        summary = "象棋。红黑对照，木盘发暖。",
        contrast = 0.16f,
        saturation = 0.02f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.08f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val wuziqi = Look(
        id = "wuziqi",
        name = "五子",
        summary = "五子。连珠冷，盘色发平。",
        contrast = 0.14f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val junqi = Look(
        id = "junqi",
        name = "军棋",
        summary = "军棋。暗子冷，棋面发旧。",
        contrast = 0.14f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.07f,
        vignette = 0.12f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val tiaoqi = Look(
        id = "tiaoqi",
        name = "跳棋",
        summary = "跳棋。彩子暖，盘色发平。",
        contrast = 0.12f,
        saturation = 0.06f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val chuhe = Look(
        id = "chuhe",
        name = "楚河",
        summary = "楚河。界河冷，字色发旧。",
        contrast = 0.12f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.08f,
        defaultIntensity = 0.50f,
    )

    val hanjie = Look(
        id = "hanjie",
        name = "汉界",
        summary = "汉界。界线暖，木纹发旧。",
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

    val jiangshuai = Look(
        id = "jiangshuai",
        name = "将帅",
        summary = "将帅。红金暖，字色发硬。",
        contrast = 0.16f,
        saturation = 0.06f,
        temperature = 0.10f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.08f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val chema = Look(
        id = "chema",
        name = "车马",
        summary = "车马。木子暖，刻字发沉。",
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

    val paoqi = Look(
        id = "paoqi",
        name = "炮",
        summary = "炮。隔子打，木色发旧。",
        contrast = 0.14f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shixiang2 = Look(
        id = "shixiang2",
        name = "士象",
        summary = "士象。守宫暖，字色发旧。",
        contrast = 0.12f,
        saturation = -0.04f,
        temperature = 0.08f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.06f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val bingzu = Look(
        id = "bingzu",
        name = "兵卒",
        summary = "兵卒。小字暖，木色发哑。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qipan = Look(
        id = "qipan",
        name = "棋盘",
        summary = "棋盘。格线冷，木色发旧。",
        contrast = 0.12f,
        saturation = -0.10f,
        temperature = 0.06f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.08f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qiheqi = Look(
        id = "qiheqi",
        name = "棋盒",
        summary = "棋盒。木盖暖，高光发哑。",
        contrast = 0.12f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.07f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qipu = Look(
        id = "qipu",
        name = "棋谱",
        summary = "棋谱。纸墨冷，谱字发旧。",
        contrast = 0.10f,
        saturation = -0.12f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.07f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val shoutan = Look(
        id = "shoutan",
        name = "手谈",
        summary = "手谈。对坐冷，茶气发薄。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.10f,
        defaultIntensity = 0.48f,
    )

    val zuozi = Look(
        id = "zuozi",
        name = "座子",
        summary = "座子。星位冷，子影发沉。",
        contrast = 0.14f,
        saturation = -0.14f,
        temperature = 0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val jiezheng = Look(
        id = "jiezheng",
        name = "劫",
        summary = "劫。争子冷，对比硬。",
        contrast = 0.18f,
        saturation = -0.14f,
        temperature = -0.02f,
        highlightRoll = 0.10f,
        shadowLift = 0.02f,
        grain = 0.05f,
        vignette = 0.12f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val guanziqi = Look(
        id = "guanziqi",
        name = "官子",
        summary = "官子。收束冷，盘边发细。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.05f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val buju = Look(
        id = "buju",
        name = "布局",
        summary = "布局。开局冷，天光发薄。",
        contrast = 0.08f,
        saturation = -0.10f,
        temperature = 0.02f,
        highlightRoll = 0.20f,
        shadowLift = 0.10f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val zhongpan = Look(
        id = "zhongpan",
        name = "中盘",
        summary = "中盘。缠绕暖，对比中。",
        contrast = 0.14f,
        saturation = -0.08f,
        temperature = 0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val shouguan = Look(
        id = "shouguan",
        name = "收官",
        summary = "收官。细目冷，盘边发静。",
        contrast = 0.10f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val xingwei = Look(
        id = "xingwei",
        name = "星位",
        summary = "星位。四点冷，盘面发清。",
        contrast = 0.10f,
        saturation = -0.14f,
        temperature = -0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val sansan = Look(
        id = "sansan",
        name = "三三",
        summary = "三三。角小冷，高光发细。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = 0.00f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.04f,
        defaultIntensity = 0.50f,
    )

    val xiaomu = Look(
        id = "xiaomu",
        name = "小目",
        summary = "小目。角位暖，子影发静。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = 0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val gaomu = Look(
        id = "gaomu",
        name = "高目",
        summary = "高目。高一路，天光发薄。",
        contrast = 0.10f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.48f,
    )

    val muwai = Look(
        id = "muwai",
        name = "目外",
        summary = "目外。更开冷，边气发阔。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.00f,
        highlightRoll = 0.18f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.08f,
        defaultIntensity = 0.48f,
    )

    val tianyuanki = Look(
        id = "tianyuanki",
        name = "天元",
        summary = "天元。盘心冷，一子发沉。",
        contrast = 0.14f,
        saturation = -0.16f,
        temperature = -0.04f,
        highlightRoll = 0.14f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.10f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val huoqi = Look(
        id = "huoqi",
        name = "活棋",
        summary = "活棋。两眼暖，气口发清。",
        contrast = 0.10f,
        saturation = -0.10f,
        temperature = 0.04f,
        highlightRoll = 0.18f,
        shadowLift = 0.10f,
        grain = 0.04f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.48f,
    )

    val siqi = Look(
        id = "siqi",
        name = "死棋",
        summary = "死棋。无眼冷，阴影沉。",
        contrast = 0.16f,
        saturation = -0.16f,
        temperature = -0.04f,
        highlightRoll = 0.12f,
        shadowLift = 0.02f,
        grain = 0.06f,
        vignette = 0.16f,
        fade = 0.06f,
        defaultIntensity = 0.54f,
    )

    val shuanghuo = Look(
        id = "shuanghuo",
        name = "双活",
        summary = "双活。共活冷，对峙发静。",
        contrast = 0.12f,
        saturation = -0.14f,
        temperature = 0.00f,
        highlightRoll = 0.16f,
        shadowLift = 0.08f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val zhengzi = Look(
        id = "zhengzi",
        name = "征子",
        summary = "征子。斜逃冷，对比硬。",
        contrast = 0.16f,
        saturation = -0.12f,
        temperature = -0.02f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val heizi = Look(
        id = "heizi",
        name = "黑子",
        summary = "黑子。近黑亮，高光发硬。",
        contrast = 0.18f,
        saturation = -0.20f,
        temperature = -0.04f,
        highlightRoll = 0.10f,
        shadowLift = 0.02f,
        grain = 0.04f,
        vignette = 0.12f,
        fade = 0.02f,
        defaultIntensity = 0.56f,
    )

    val baiziqi = Look(
        id = "baiziqi",
        name = "白子",
        summary = "白子。冷白硬，高光发薄。",
        contrast = 0.08f,
        saturation = -0.16f,
        temperature = -0.02f,
        highlightRoll = 0.22f,
        shadowLift = 0.12f,
        grain = 0.03f,
        vignette = 0.06f,
        fade = 0.06f,
        defaultIntensity = 0.48f,
    )

    val jinqiao = Look(
        id = "jinqiao",
        name = "金角",
        summary = "金角。角地暖，子密发沉。",
        contrast = 0.14f,
        saturation = -0.06f,
        temperature = 0.08f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        splitHighlightWarm = 0.06f,
        grain = 0.05f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.52f,
    )

    val yinbian = Look(
        id = "yinbian",
        name = "银边",
        summary = "银边。边地冷，气口发清。",
        contrast = 0.12f,
        saturation = -0.12f,
        temperature = -0.04f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val duizi = Look(
        id = "duizi",
        name = "对子",
        summary = "对子。并排冷，间距发静。",
        contrast = 0.12f,
        saturation = -0.14f,
        temperature = 0.00f,
        highlightRoll = 0.16f,
        shadowLift = 0.06f,
        grain = 0.04f,
        vignette = 0.08f,
        fade = 0.06f,
        defaultIntensity = 0.50f,
    )

    val qishi = Look(
        id = "qishi",
        name = "棋势",
        summary = "棋势。厚薄对照，盘气发沉。",
        contrast = 0.14f,
        saturation = -0.12f,
        temperature = 0.02f,
        highlightRoll = 0.14f,
        shadowLift = 0.04f,
        grain = 0.05f,
        vignette = 0.12f,
        fade = 0.06f,
        defaultIntensity = 0.52f,
    )

    val luozi = Look(
        id = "luozi",
        name = "落子",
        summary = "落子。一子冷，声影发硬。",
        contrast = 0.16f,
        saturation = -0.14f,
        temperature = -0.02f,
        highlightRoll = 0.12f,
        shadowLift = 0.04f,
        grain = 0.04f,
        vignette = 0.10f,
        fade = 0.04f,
        defaultIntensity = 0.54f,
    )

    val all: List<Look> = listOf(
        weiqi, xiangqi, wuziqi, junqi, tiaoqi, chuhe, hanjie, jiangshuai, chema, paoqi,
        shixiang2, bingzu, qipan, qiheqi, qipu, shoutan, zuozi, jiezheng, guanziqi, buju,
        zhongpan, shouguan, xingwei, sansan, xiaomu, gaomu, muwai, tianyuanki, huoqi, siqi,
        shuanghuo, zhengzi, heizi, baiziqi, jinqiao, yinbian, duizi, qishi, luozi,
    )
}
