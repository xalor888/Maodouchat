package com.maodouchat.util

/**
 * 群玩法只读数据表（从 GroupPlayPolicy.kt 拆出，纯数据，无逻辑）。
 */

internal val rpsChoices = listOf("rock", "paper", "scissors")


internal val truthPrompts = listOf(
        "最近一次让你开心的小事是什么？",
        "如果明天放假，你会去做什么？",
        "你最想感谢群里谁？为什么？",
        "分享一个冷知识。",
        "你最近在追什么剧/书/游戏？"
    )


internal val wordChainSeeds = listOf("apple", "echo", "ocean", "night", "team", "music", "cloud")


internal val raceTokens = listOf("FAST", "FIRE", "TARGET", "ROCKET", "LUCKY")


internal val wouldPrompts = listOf(
        "Beach vacation|Mountain cabin",
        "Only spicy food|Only sweet food",
        "Always early|Always late",
        "Talk to animals|Speak every language",
        "Time travel past|Time travel future"
    )


internal val rainEmojis = listOf("🎉", "✨", "🔥", "💚", "⭐", "🎯")


internal val quizBank = listOf(
        "What never asks questions but is often answered?|doorbell|phone|door",
        "I have keys but no locks. What am I?|keyboard|map|piano",
        "The more you take, the more you leave behind. What are they?|footsteps|photos|money"
    )


internal val spinOptions = listOf(
        "Truth", "Dare", "Drink water", "Sing 10s", "Praise someone", "Free pass"
    )


internal val bingoEmojis = listOf("🍎", "🚗", "🌟", "🎯", "🐶", "🎵", "🍀", "🔥", "💎", "🌈")


internal val charadesPrompts = listOf(
        "elephant", "rocket", "sushi", "detective", "rainbow",
        "panda", "skyscraper", "violin", "pirate", "volcano"
    )


internal val riddles = listOf(
        "What has keys but no locks?" to "keyboard",
        "What gets wetter as it dries?" to "towel",
        "I speak without a mouth. What am I?" to "echo",
        "有头无脚，有尾无身？" to "硬币",
        "什么东西越洗越脏？" to "水"
    )


internal val emojiStorySeeds = listOf("🚀🌙👽", "🐱🍜💤", "🕵️‍♂️🔑🚪", "🌋🏃‍♂️😱", "🎓📚💡")


internal val simonTokens = listOf("🔴", "🟢", "🔵", "🟡", "🟣", "⚪")


internal val triviaQA = listOf(
        "Capital of France?" to "Paris",
        "2+2*2=?" to "6",
        "地球绕太阳一圈大约几天？" to "365",
        "HTTP default port?" to "80",
        "Signal protocol base?" to "Double Ratchet"
    )


internal val dares = listOf(
        "用方言唱一句歌",
        "发一条语音说绕口令",
        "描述昨晚的梦",
        "Send a voice note of your best animal impression",
        "Tell a joke without laughing"
    )

internal val neverHave = listOf(
        "Never have I ever forgotten a password",
        "Never have I ever ghosted a group chat",
        "我从来没有熬夜追剧到凌晨",
        "我从来没有发错过人",
        "Never have I ever used AI to write a message"
    )

internal val drawPrompts = listOf("一只戴墨镜的猫", "会飞的茶壶", "雨中的机器人", "A sleepy dragon", "City on a cloud")

internal val memoryEmojis = listOf("🍎🍌🍇🍉", "🐶🐱🐰🦊", "🚗✈️🚀🛸", "🎹🎸🥁🎺")


internal val icebreakers = listOf(
        "本周最开心的一件事？",
        "如果明天放假你会做什么？",
        "最近在追什么剧/书？",
        "What song is stuck in your head?",
        "If you could teleport once, where?"
    )

internal val duelEmojis = listOf("⚔️🛡️", "🔥❄️", "🐱🐶", "🍕🍣", "🎸🎹")

internal val rapidTopics = listOf("水果", "城市", "动物", "movies", "apps", "colors")


internal val scatterLetters = ('A'..'Z').map { it.toString() }

internal val scatterCats = listOf("动物", "食物", "城市", "movie", "app", "color")

internal val talkTopics = listOf(
        "最喜欢的旅行",
        "如果中奖了",
        "童年回忆",
        "A skill you want to learn",
        "Best meal this year"
    )

internal val captionSeeds = listOf("🐱📸", "🌧️🏙️", "🚀🍕", "A blank stare", "Unexpected plot twist")


internal val storyOpeners = listOf(
        "突然手机响了…",
        "电梯停在了13楼…",
        "A stranger handed me a key…",
        "The lights went out mid-sentence…"
    )

internal val karaokeLines = listOf(
        "唱一句你最尴尬的副歌",
        "用气声唱 HAPPY BIRTHDAY",
        "Hum the chorus of a hit song",
        "Rap one line about today"
    )

internal val blindQs = listOf(
        "对方最讨厌的食物是？",
        "对方理想的周末？",
        "What would they pack for a trip?",
        "Their comfort movie?"
    )


internal val fortunes = listOf(
        "今日宜密聊，忌截图",
        "会有小惊喜，别熬夜",
        "A calm chat clears the fog",
        "Send kindness first",
        "好运藏在未读消息里"
    )

internal val emojiQuiz = listOf(
        "🍎📱" to "apple phone / iPhone",
        "🌧️☂️" to "rain umbrella",
        "🎬🍿" to "movie night",
        "🐱🧶" to "cat yarn"
    )

internal val chainSeeds = listOf("🚀", "🎵", "🌊", "🔥", "🍀")


internal val debateTopics = listOf(
        "远程办公 vs 办公室",
        "早起 vs 夜猫子",
        "猫 vs 狗",
        "Plaintext notes vs encrypted vaults",
        "Tabs vs spaces"
    )

internal val mirrorLines = listOf(
        "今天想对你说一句谢谢",
        "把这句话用你的方式再说一遍",
        "Mirror this: privacy first",
        "跟读：端到端加密保护我们"
    )

internal val hideEmojis = listOf("🐸", "🦄", "🦊", "🐼", "🐧", "🐝")

internal val roastLines = listOf(
        "你打字像在赶高铁",
        "这条消息比我闹钟还准时",
        "Friendly roast: 你收藏夹比聊天还活跃",
        "吐槽局：你的已读不回是艺术"
    )


internal val potatoSeconds = listOf(8, 10, 12, 15)

internal val wordHints = listOf(
        "水果 · 红色 · 圆" to "苹果",
        "动物 · 长鼻子" to "大象",
        "city · lights · tower" to "Paris",
        "密聊 · 防截图" to "盲水印"
    )


internal val spyLocations = listOf("太空站", "游轮", "银行", "机场", "医院", "School", "Beach", "Museum")

internal val acrosticSeeds = listOf("密聊", "安全", "隐私", "丝滑", "PEACE", "LIGHT")

internal val emojiTr = listOf(
        "🌙📚" to "熬夜学习",
        "🏃‍♂️💨" to "赶紧跑",
        "🔐💬" to "加密聊天",
        "🍕🎉" to "pizza party"
    )


internal val twentySubjects = listOf("一种水果", "一种动物", "一个城市", "a movie", "an app")

internal val rhymeSeeds = listOf("花", "光", "night", "blue", "心")

internal val oddSets = listOf(
        "猫|狗|鸟|汽车" to "汽车",
        "苹果|香蕉|石头|葡萄" to "石头",
        "TLS|E2EE|明文|Signal" to "明文"
    )


internal val categories = listOf("水果", "城市", "动物", "App", "电影")

internal val passwordHints = listOf("8位·含数字", "只有小写", "与密聊有关", "no spaces")

internal val capsules = listOf(
        "写给未来的自己：记得开密聊",
        "一周后打开：你会感谢今天的坚持",
        "给群友的祝福，先封存"
    )


internal val tabooCards = listOf(
        "密聊|截图|水印|加密",
        "火箭|太空|月球|NASA",
        "咖啡|拿铁|浓缩|豆",
        "Telegram|贴纸|频道|机器人",
    )

internal val lightningPrompts = listOf(
        "10 秒内说出 3 个水果",
        "快速接龙：城市名",
        "一口气介绍你最爱的 App",
        "Lightning: 3 emoji story",
    )

internal val twoWordSeeds = listOf("月光", "键盘", "盲水印", "signal", "毛豆")


internal val whisperPrompts = listOf(
        "悄悄话：说出一个只有群友懂的梗",
        "Whisper a secret emoji code",
        "用三词描述今天的心情",
        "传话：把这句话变可爱一点",
    )

internal val countdownRaceSeeds = listOf(3, 5, 10)


internal val emojiMemoryBoards = listOf("🍎🍋🍇🍉", "🐶🐱🐭🐹", "🚀🌟🌙☀️")

internal val geoClues = listOf("东方明珠所在城市", "Eiffel Tower city", "富士山所在国家", "Great Wall country")


internal val oneWords = listOf("密聊", "月光", "火箭", "signal", "毛豆")

internal val mathQs = listOf("7+8", "12-5", "6*3", "20/4", "9+16")

internal val storySeeds = listOf("雨夜的火车站", "一台会说话的手机", "群里的神秘机器人", "a sealed envelope")


internal val wouldPairs2 = listOf(
        "永远密聊" to "永远阅后即焚",
        "只发语音" to "只发文字",
        "coffee forever" to "tea forever",
    )

internal val emojiOnlyPrompts = listOf("用 3 个 emoji 形容今天", "emoji-only movie title", "用 emoji 讲个笑话")

internal val blindDraws = listOf("🐱", "🚀", "🍉", "🔑", "🌙")


internal val alphabetStarts = listOf("A", "B", "M", "S", "Mao", "Dou")

internal val silentMovies = listOf("basketball", "hotpot", "train", "writing code", "secret chat")

internal val colorWords = listOf("red-apple", "blue-ocean", "green-tea", "yellow-lemon", "purple-grape")


internal val debateFlashTopics = listOf("cats vs dogs", "tea vs coffee", "early bird vs night owl", "phone vs laptop")

internal val emojiStories = listOf("🚀🌙🏠", "🍉📱💡", "🐱🔑🚪")

internal val quickPolls = listOf("pizza|sushi|tacos", "beach|mountain|city", "movie|game|music")


internal val mirrorEchoLines = listOf("I am calm", "We ship tonight", "Secret chats stay secret", "Hello mirror")

internal val clapCounts = listOf("3", "5", "7")

internal val facts = listOf("Earth is round|true", "Fish climb trees|false", "Signal is E2EE|true")


internal val impulseDraws = listOf("🎯", "🎲", "🎁", "🍀", "🔥")

internal val scrambles = listOf("signal|signla", "maodou|uodoma", "secret|creste", "encrypt|ypcretn")

internal val reactionDuels = listOf("👍|👎", "❤️|💙", "😂|😭")


internal val codes = listOf("0421", "1337", "9080", "2468")

internal val sillyLaws = listOf("No spoilers before coffee", "Only whisper secrets", "Emoji first, words second")

internal val emojiMaths = listOf("🍎+🍎=2", "🚀-🌙=?", "🐱x2=?")


internal val moods = listOf("calm", "chaotic", "focused", "cozy")

internal val rushWindows = listOf("10s", "20s", "30s")

internal val secretSignals = listOf("knock-knock", "two-taps", "blue-moon")


internal val ideaSeeds = listOf("startup", "weekend trip", "side project")

internal val tempoBeats = listOf("60bpm", "90bpm", "120bpm")

internal val translatePairs = listOf("zh->en", "en->ja", "emoji->words")


internal val inviteRaces = listOf("first-join", "scan-race", "token-dash")

internal val mentionModes = listOf("@all wave", "name chain", "silent ping")

internal val linkHunts = listOf("hidden token", "clue trail", "qr blitz")


internal val nudgeDashes = listOf("double-tap", "wave train", "pet stampede")

internal val codeChecks = listOf("digit duel", "fingerprint flash", "code echo")

internal val trustSprints = listOf("verify pair", "qr race", "trust ladder")


internal val moodMeters = listOf("1-10 energy", "calm-chaotic", "solo-social")

internal val focusSprints = listOf("5m", "15m", "25m")

internal val gratitudePrompts = listOf("one win", "one helper", "one hope")


internal val qrQuests = listOf("badge hunt", "poster scan", "mirror match")

internal val contactSwaps = listOf("intro card", "phone free", "alias exchange")

internal val scanSprints = listOf("3s focus", "steady hands", "frame race")


internal val spoilerRaces = listOf("tap-to-reveal", "no peeking", "fog lift")

internal val blurBattles = listOf("guess the shot", "edge only", "silhouette")

internal val downloadDashes = listOf("wifi only", "instant save", "queue clear")


internal val pinDrops = listOf("cafe pin", "meetup spot", "silent drop")

internal val fileRelays = listOf("pass the pdf", "zip chain", "doc dash")

internal val mapDashes = listOf("north star", "grid hop", "route race")

internal val vaultLocks = listOf("seal the vault", "key swap", "silent vault")

internal val wmHunts = listOf("find the mark", "timestamp trail", "id echo")

internal val secureSprints = listOf("flag secure", "recents hide", "capture alert")


internal val photoRaces = listOf("snap first", "angle challenge", "color match")

internal val clipDashes = listOf("3s clip", "silent film", "cut race")

internal val frameHunts = listOf("hidden detail", "pixel hunt", "border clue")

internal val summaryCircles = listOf("one-line recap", "bullet pass", "decision digest")

internal val rewriteRelays = listOf("polish pass", "tone shift", "shorten chain")

internal val promptSprints = listOf("better ask", "context pack", "goal clear")

internal val suggestCircles = listOf("quick reply", "tone pick", "emoji soft")

internal val voiceRaces = listOf("10s note", "clear speak", "no noise")

internal val replySprints = listOf("three options", "yes-no soft", "next step")

internal val pixelQuests = listOf("hidden object", "color code", "tiny text")

internal val assistCircles = listOf("decision recap", "risk list", "owner map")

internal val decisionDashes = listOf("vote now", "two choices", "deadline pick")

internal val docHunts = listOf("clause find", "table scan", "signature hunt")

internal val meaningRaces = listOf("intent match", "synonym dash", "context win")

internal val insightSprints = listOf("one takeaway", "risk flash", "next action")

internal val gifRelays = listOf("loop pass", "reaction chain", "meme baton")

internal val markHunts = listOf("find stamp", "spot id", "time tag")

internal val leakSprints = listOf("no share", "seal leak", "trace dash")

internal val voiceRings = listOf("answer first", "mute challenge", "hold music")

internal val videoStages = listOf("cam ready", "light check", "face frame")

internal val ringDashes = listOf("pick up", "decline race", "missed sprint")

internal val wallPicks = listOf("mint mood", "night vibe", "rose glow")

internal val fontRaces = listOf("read big", "tiny type", "scale duel")

internal val themeSprints = listOf("palette flash", "contrast check", "comfort pick")

internal val unreadRushes = listOf("clear first", "badge dash", "inbox sprint")

internal val ringChoirs = listOf("tone match", "quiet duel", "melody pick")

internal val alertSprints = listOf("ping race", "mute check", "notice flash")

internal val soundWaves = listOf("tone pass", "volume duel", "silent chain")

internal val previewMasks = listOf("hide body", "blur title", "lock preview")

internal val beepDashes = listOf("first beep", "double ping", "echo race")

internal val pushRaces = listOf("deliver first", "badge chase", "silent push")

internal val remindCircles = listOf("nudge loop", "due soon", "snooze duel")

internal val wakeSprints = listOf("wake ping", "morning alert", "focus chime")

internal val quietHours = listOf("mute window", "night shield", "focus block")

internal val offlineHints = listOf("no cloud", "local smart", "cache reply")

internal val fallbackDashes = listOf("plan b", "heuristic win", "offline first")

internal val clickBeats = listOf("tap tempo", "soft click", "double tap")

internal val buzzRelays = listOf("buzz pass", "vibrate chain", "pulse baton")

internal val feelSprints = listOf("smooth feel", "silk motion", "comfort tap")

internal val slideRaces = listOf("page glide", "edge sweep", "panel slide")

internal val fadeCircles = listOf("soft fade", "dim pass", "opacity duel")

internal val springDashes = listOf("bounce in", "overshoot", "settle soft")

internal val snapGuards = listOf("no snap", "block capture", "alert first")

internal val recentsHides = listOf("hide card", "blank task", "no preview")

internal val shieldSprints = listOf("seal screen", "secure race", "privacy dash")

internal val copyLocks = listOf("no paste", "clip seal", "text vault")

internal val exportSeals = listOf("no share", "gallery lock", "file seal")

internal val leakWalls = listOf("hold line", "stop leak", "vault wall")

internal val forwardSeals = listOf("no forward", "relay seal", "path lock")

internal val chatExportLocks = listOf("no dump", "history seal", "json lock")

internal val vaultFences = listOf("keep inside", "vault rim", "fence hold")

internal val sealSprints = listOf("cert ready", "hide hop", "seal pass")

internal val pqxdhDashes = listOf("pq handshake", "future proof", "lattice dash")

internal val certRelays = listOf("issue chain", "rotate cert", "ttl race")

internal val markSprints = listOf("stamp id", "time tag", "visible mark")

internal val fadeTimers = listOf("24h auto", "timer on", "fade start")

internal val stampRelays = listOf("pass stamp", "mark baton", "uid trail")

internal val linkLocks = listOf("no open", "url seal", "tap block")

internal val previewMutes = listOf("no fetch", "meta mute", "og off")

internal val urlFences = listOf("stay in chat", "no browser", "fence url")

internal val notifMasks = listOf("hide body", "generic tray", "no sender")

internal val listBlurs = listOf("blur last", "no draft", "secret row")

internal val traySeals = listOf("tray lock", "banner mute", "status seal")

internal val reactLocks = listOf("emoji freeze", "react mute", "heart seal")

internal val starSeals = listOf("no favorites", "star mute", "bookmark seal")

internal val metaFences = listOf("meta silence", "trace fence", "side-channel lock")

internal val typingSeals = listOf("typing silence", "presence freeze", "input seal")

internal val readSeals = listOf("no read", "seen mute", "receipt seal")

internal val presenceSeals = listOf("presence silence", "online freeze", "status seal")

internal val lastSeenSeals = listOf("no last seen", "timestamp mute", "trace seal")


