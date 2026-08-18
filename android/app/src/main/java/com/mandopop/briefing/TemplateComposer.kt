package com.mandopop.briefing

/**
 * The no-model composer — briefing v1, and the permanent fallback behind the model.
 *
 * Human-authored frames slot-filled from the plan, correct by construction, so the pipeline
 * degrades to "correct but boring", never to absent or wrong. Every candidate still goes through
 * the verifier: a frame is only as known as the words filling it, so candidates are ordered from
 * most specific to most universally-known and the first one that verifies wins.
 */
object TemplateComposer {

    fun candidates(plan: BriefingPicker.Plan): List<String> {
        val topic = plan.topic
        val time = plan.timeOfDay
        return when (plan.kind) {
            BriefingPicker.SourceKind.CALENDAR -> buildList {
                if (topic != null && time != null) add("你今天${time}有${topic}。")
                if (topic != null) add("你今天有${topic}。")
                if (time != null) {
                    add("你今天${time}有安排。")
                    add("你今天${time}有事。")
                }
                add("你今天有安排。")
                add("你今天有事。")
            }
            BriefingPicker.SourceKind.NOTIFICATION -> buildList {
                if (topic != null) add("你有关于${topic}的新消息。")
                add("你有新消息。")
                add("有人给你发了消息。")
            }
            BriefingPicker.SourceKind.SCREEN -> buildList {
                if (topic != null) add("你在看关于${topic}的东西。")
                if (topic != null) add("你在看${topic}。")
                add("你在看手机。")
            }
        }
    }
}
