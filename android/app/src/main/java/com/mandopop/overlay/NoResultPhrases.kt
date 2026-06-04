package com.mandopop.overlay

import com.mandopop.dictionary.CedictEntry

object NoResultPhrases {
    private val phrases = listOf(
        CedictEntry("看不懂", "kàn bù dǒng", listOf("can't understand what I'm seeing")),
        CedictEntry("没听过", "méi tīngguò", listOf("never heard of it")),
        CedictEntry("问倒我了", "wèn dǎo wǒ le", listOf("you've stumped me")),
        CedictEntry("我的中文还要加油", "wǒ de Zhōngwén hái yào jiāyóu", listOf("my Chinese still needs work")),
        CedictEntry("字典也没办法", "zìdiǎn yě méi bànfǎ", listOf("even the dictionary can't help")),
        CedictEntry("这个嘛……", "zhège ma...", listOf("well, this...")),
        CedictEntry("蛤？", "há?", listOf("huh?")),
        CedictEntry("天啊，这什么？", "tiān a, zhè shénme?", listOf("heavens, what is this?")),
        CedictEntry("没有头绪", "méiyǒu tóuxù", listOf("no clue")),
        CedictEntry("我想一下", "wǒ xiǎng yíxià", listOf("let me think a moment")),
        CedictEntry("不知道怎么说", "bù zhīdào zěnme shuō", listOf("don't know how to say it")),
        CedictEntry("这个我真的不会", "zhège wǒ zhēnde bú huì", listOf("this one I really don't know")),
        CedictEntry("学到老，还是不会", "xué dào lǎo, háishì bú huì", listOf("study till old age, still won't know")),
        CedictEntry("找不到，但没关系", "zhǎo bú dào, dàn méi guānxì", listOf("can't find it, but no worries")),
        CedictEntry("我也不知道耶", "wǒ yě bù zhīdào yē", listOf("I don't know either")),
    )

    fun random(): CedictEntry = phrases.random()
}
