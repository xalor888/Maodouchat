package com.maodouchat.util

/**
 * 群玩法经典游戏只读数据表（从 GroupPlayData.kt 拆出，纯数据，无逻辑）。
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
