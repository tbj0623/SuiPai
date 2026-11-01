package com.suipai.core.engine

object LookLibraryCanal {
    val ganqugouqu = Look(
        id = "ganqugouqu", name = "干渠", summary = "干渠。渠面阔，水色发冷，高光发滑。",
        contrast = 0.12f, saturation = -0.06f, temperature = -0.04f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitShadowBlue = 0.08f,
        grain = 0.05f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val zhiqugouqu = Look(
        id = "zhiqugouqu", name = "支渠", summary = "支渠。渠较窄，岸草发绿，高光发哑。",
        contrast = 0.12f, saturation = 0.00f, temperature = 0.02f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.06f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val douqugouqu = Look(
        id = "douqugouqu", name = "斗渠", summary = "斗渠。斗口小，泥岸发灰，高光发干。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val nongqugouqu = Look(
        id = "nongqugouqu", name = "农渠", summary = "农渠。田间渠，水色发浊，高光发哑。",
        contrast = 0.12f, saturation = 0.02f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.08f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val maoqugouqu = Look(
        id = "maoqugouqu", name = "毛渠", summary = "毛渠。毛渠细，草边发密，高光发软。",
        contrast = 0.10f, saturation = 0.02f, temperature = 0.04f,
        highlightRoll = 0.18f, shadowLift = 0.08f, grain = 0.06f,
        vignette = 0.08f, fade = 0.08f, softness = 0.04f, defaultIntensity = 0.50f,
    )
    val qukougouqu = Look(
        id = "qukougouqu", name = "渠口", summary = "渠口。进水急，浪花发白，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = -0.02f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.12f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val quweigouqu = Look(
        id = "quweigouqu", name = "渠尾", summary = "渠尾。水势缓，淤泥发灰，高光发哑。",
        contrast = 0.12f, saturation = -0.04f, temperature = 0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val quangouqu = Look(
        id = "quangouqu", name = "渠岸", summary = "渠岸。岸土湿，草根发绿，高光发干。",
        contrast = 0.12f, saturation = 0.02f, temperature = 0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val qudigouqu = Look(
        id = "qudigouqu", name = "渠底", summary = "渠底。底泥沉，水色发深，高光发滑。",
        contrast = 0.14f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.16f, shadowLift = 0.04f, grain = 0.07f,
        vignette = 0.14f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val qupogouqu = Look(
        id = "qupogouqu", name = "渠坡", summary = "渠坡。坡面斜，草皮发干，高光发哑。",
        contrast = 0.14f, saturation = 0.00f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val quzhagouqu = Look(
        id = "quzhagouqu", name = "渠闸", summary = "渠闸。闸板厚，水痕发冷，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.06f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val quqiaogouqu = Look(
        id = "quqiaogouqu", name = "渠桥", summary = "渠桥。桥面窄，倒影发冷，高光发纸。",
        contrast = 0.12f, saturation = -0.04f, temperature = 0.02f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitShadowBlue = 0.06f,
        grain = 0.05f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val quhangouqu = Look(
        id = "quhangouqu", name = "渠涵", summary = "渠涵。涵洞暗，湿壁发冷，高光发滑。",
        contrast = 0.14f, saturation = -0.08f, temperature = -0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, splitShadowBlue = 0.10f,
        grain = 0.06f, vignette = 0.16f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val qudigouqu2 = Look(
        id = "qutigouqu", name = "渠堤", summary = "渠堤。堤顶平，黄土发干，高光发哑。",
        contrast = 0.14f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.16f, shadowLift = 0.06f, splitHighlightWarm = 0.08f,
        grain = 0.08f, vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val qucaogouqu = Look(
        id = "qucaogouqu", name = "渠草", summary = "渠草。水草密，绿影发闷，高光发软。",
        contrast = 0.10f, saturation = 0.04f, temperature = 0.00f,
        highlightRoll = 0.18f, shadowLift = 0.08f, grain = 0.06f,
        vignette = 0.08f, fade = 0.08f, softness = 0.04f, defaultIntensity = 0.50f,
    )
    val qunigouqu = Look(
        id = "qunigouqu", name = "渠泥", summary = "渠泥。泥厚黏，褐灰发闷，高光发哑。",
        contrast = 0.12f, saturation = 0.00f, temperature = 0.08f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.09f,
        vignette = 0.10f, fade = 0.08f, defaultIntensity = 0.52f,
    )
    val qushuigouqu = Look(
        id = "qushuigouqu", name = "渠水", summary = "渠水。水面平，倒影发冷，高光发滑。",
        contrast = 0.10f, saturation = -0.06f, temperature = -0.04f,
        highlightRoll = 0.20f, shadowLift = 0.10f, splitShadowBlue = 0.08f,
        grain = 0.04f, vignette = 0.08f, fade = 0.08f, softness = 0.04f, defaultIntensity = 0.50f,
    )
    val quyinggouqu = Look(
        id = "quyinggouqu", name = "渠影", summary = "渠影。影细长，水纹发碎，高光发纸。",
        contrast = 0.12f, saturation = -0.08f, temperature = -0.02f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitShadowBlue = 0.08f,
        grain = 0.04f, vignette = 0.10f, fade = 0.08f, defaultIntensity = 0.52f,
    )
    val qubinggouqu = Look(
        id = "qubinggouqu", name = "渠冰", summary = "渠冰。冰壳薄，寒气发刺，高光发硬。",
        contrast = 0.16f, saturation = -0.12f, temperature = -0.10f,
        highlightRoll = 0.12f, shadowLift = 0.04f, splitShadowBlue = 0.12f,
        grain = 0.03f, vignette = 0.10f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val quwugouqu = Look(
        id = "quwugouqu", name = "渠雾", summary = "渠雾。雾贴水，远岸发糊，高光发纸。",
        contrast = 0.06f, saturation = -0.10f, temperature = 0.00f,
        highlightRoll = 0.24f, shadowLift = 0.14f, grain = 0.03f,
        vignette = 0.06f, fade = 0.14f, softness = 0.10f, defaultIntensity = 0.48f,
    )
    val quchengouqu = Look(
        id = "quchengouqu", name = "渠晨", summary = "渠晨。晨雾薄，水色发冷，高光发软。",
        contrast = 0.10f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.20f, shadowLift = 0.10f, grain = 0.04f,
        vignette = 0.08f, fade = 0.08f, softness = 0.06f, defaultIntensity = 0.50f,
    )
    val qumugouqu = Look(
        id = "qumugouqu", name = "渠暮", summary = "渠暮。暮红沉，水面发暖，高光发软。",
        contrast = 0.12f, saturation = 0.04f, temperature = 0.10f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.10f,
        grain = 0.05f, vignette = 0.12f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val quyugouqu = Look(
        id = "quyugouqu", name = "渠雨", summary = "渠雨。雨点密，水面发碎，高光发滑。",
        contrast = 0.12f, saturation = -0.06f, temperature = -0.04f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitShadowBlue = 0.08f,
        grain = 0.05f, vignette = 0.10f, fade = 0.08f, defaultIntensity = 0.52f,
    )
    val qufenggouqu = Look(
        id = "qufenggouqu", name = "渠风", summary = "渠风。风过波，涟漪发白，高光发纸。",
        contrast = 0.10f, saturation = -0.04f, temperature = 0.02f,
        highlightRoll = 0.20f, shadowLift = 0.10f, grain = 0.04f,
        vignette = 0.06f, fade = 0.08f, softness = 0.06f, defaultIntensity = 0.50f,
    )
    val shiqugouqu = Look(
        id = "shiqugouqu", name = "石渠", summary = "石渠。石砌齐，缝苔发绿，高光发干。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.04f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val tuqugouqu = Look(
        id = "tuqugouqu", name = "土渠", summary = "土渠。土壁松，泥色发暖，高光发哑。",
        contrast = 0.12f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.16f, shadowLift = 0.06f, splitHighlightWarm = 0.08f,
        grain = 0.08f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val zhuanqugouqu = Look(
        id = "zhuanqugouqu", name = "砖渠", summary = "砖渠。砖缝齐，水色发冷，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.06f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val mucaoqugouqu = Look(
        id = "mucaoqugouqu", name = "木槽渠", summary = "木槽渠。槽木湿，油色发暖，高光发黏。",
        contrast = 0.12f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.08f,
        grain = 0.07f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val anqugouqu = Look(
        id = "anqugouqu", name = "暗渠", summary = "暗渠。渠在暗，湿气发冷，高光发哑。",
        contrast = 0.16f, saturation = -0.10f, temperature = -0.04f,
        highlightRoll = 0.14f, shadowLift = 0.04f, splitShadowBlue = 0.10f,
        grain = 0.06f, vignette = 0.18f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val mingqugouqu = Look(
        id = "mingqugouqu", name = "明渠", summary = "明渠。天光满，水面发亮，高光发硬。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.06f, grain = 0.05f,
        vignette = 0.08f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val tuishuiqugouqu = Look(
        id = "tuishuiqugouqu", name = "退水渠", summary = "退水渠。退水急，泡沫发白，高光发燥。",
        contrast = 0.16f, saturation = -0.04f, temperature = 0.00f,
        highlightRoll = 0.12f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.12f, fade = 0.02f, defaultIntensity = 0.56f,
    )
    val jinshuiqugouqu = Look(
        id = "jinshuiqugouqu", name = "进水渠", summary = "进水渠。来水清，波纹发冷，高光发滑。",
        contrast = 0.12f, saturation = -0.06f, temperature = -0.04f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitShadowBlue = 0.08f,
        grain = 0.04f, vignette = 0.10f, fade = 0.06f, defaultIntensity = 0.52f,
    )
    val fenshuikougouqu = Look(
        id = "fenshuikougouqu", name = "分水口", summary = "分水口。口门窄，分流发急，高光发硬。",
        contrast = 0.16f, saturation = -0.04f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val dieshuigouqu = Look(
        id = "dieshuigouqu", name = "跌水", summary = "跌水。落差白，水花发炸，高光发刺。",
        contrast = 0.18f, saturation = -0.06f, temperature = -0.02f,
        highlightRoll = 0.10f, shadowLift = 0.02f, grain = 0.04f,
        vignette = 0.12f, fade = 0.02f, defaultIntensity = 0.58f,
    )
    val ducaogouqu = Look(
        id = "ducaogouqu", name = "渡槽", summary = "渡槽。槽架空，水线发亮，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.05f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val daohongxigouqu = Look(
        id = "daohongxigouqu", name = "倒虹吸", summary = "倒虹吸。管潜入，水面发暗，高光发滑。",
        contrast = 0.16f, saturation = -0.08f, temperature = -0.04f,
        highlightRoll = 0.14f, shadowLift = 0.04f, splitShadowBlue = 0.10f,
        grain = 0.05f, vignette = 0.14f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val qubiaogouqu = Look(
        id = "qubiaogouqu", name = "渠标", summary = "渠标。标杆立，字迹发旧，高光发干。",
        contrast = 0.14f, saturation = -0.02f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.07f,
        vignette = 0.10f, fade = 0.04f, defaultIntensity = 0.54f,
    )
    val quzhuanggouqu = Look(
        id = "quzhuanggouqu", name = "渠桩", summary = "渠桩。桩木湿，水渍发灰，高光发哑。",
        contrast = 0.14f, saturation = -0.04f, temperature = 0.06f,
        highlightRoll = 0.16f, shadowLift = 0.06f, grain = 0.08f,
        vignette = 0.12f, fade = 0.06f, defaultIntensity = 0.54f,
    )
    val quzhabangouqu = Look(
        id = "quzhabangouqu", name = "渠闸板", summary = "渠闸板。板缝紧，水痕发冷，高光发硬。",
        contrast = 0.16f, saturation = -0.06f, temperature = 0.02f,
        highlightRoll = 0.14f, shadowLift = 0.04f, grain = 0.06f,
        vignette = 0.12f, fade = 0.04f, defaultIntensity = 0.56f,
    )
    val qudenggouqu = Look(
        id = "qudenggouqu", name = "渠灯", summary = "渠灯。灯影长，水面发暖，高光发软。",
        contrast = 0.12f, saturation = 0.02f, temperature = 0.10f,
        highlightRoll = 0.18f, shadowLift = 0.08f, splitHighlightWarm = 0.10f,
        grain = 0.05f, vignette = 0.14f, fade = 0.06f, softness = 0.04f, defaultIntensity = 0.52f,
    )

    val all: List<Look> = listOf(
        ganqugouqu, zhiqugouqu, douqugouqu, nongqugouqu, maoqugouqu, qukougouqu, quweigouqu, quangouqu, qudigouqu, qupogouqu,
        quzhagouqu, quqiaogouqu, quhangouqu, qudigouqu2, qucaogouqu, qunigouqu, qushuigouqu, quyinggouqu, qubinggouqu, quwugouqu,
        quchengouqu, qumugouqu, quyugouqu, qufenggouqu, shiqugouqu, tuqugouqu, zhuanqugouqu, mucaoqugouqu, anqugouqu, mingqugouqu,
        tuishuiqugouqu, jinshuiqugouqu, fenshuikougouqu, dieshuigouqu, ducaogouqu, daohongxigouqu, qubiaogouqu, quzhuanggouqu, quzhabangouqu, qudenggouqu,
    )
}
