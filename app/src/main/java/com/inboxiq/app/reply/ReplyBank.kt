package com.inboxiq.app.reply

/**
 * The built-in wording. Each option is one *job* ([Stance]) with a few phrasings per tone; the engine
 * swaps in the user's own phrasing for that job when it has one, and otherwise picks a variant by a stable
 * hash of the incoming text (so the chips do not change on every redraw, but do vary between messages).
 *
 * Rules for wording: short, no invented facts (no times, places, names, numbers), no links, nothing that
 * commits the user to money, and nothing cheerful in reply to bad news.
 */
internal class Opt(
    val stance: Stance,
    val formal: List<String>,
    val neutral: List<String>,
    val casual: List<String>,
    /** May carry the user's favourite emoji. */
    val warm: Boolean = false,
) {
    fun pick(tone: Tone, h: Int): String {
        val list = when (tone) {
            Tone.FORMAL -> formal.ifEmpty { neutral }
            Tone.CASUAL -> casual.ifEmpty { neutral }
            Tone.NEUTRAL -> neutral
        }
        return list[Math.floorMod(h, list.size)]
    }
}

private fun o(stance: Stance, neutral: List<String>, formal: List<String> = neutral, casual: List<String> = neutral, warm: Boolean = false) =
    Opt(stance, formal, neutral, casual, warm)

internal object ReplyBank {

    fun options(intent: ReplyIntent, sub: ReplySub): List<Opt> = when (intent) {
        ReplyIntent.YES_NO -> yesNo(sub)
        ReplyIntent.EITHER_OR -> emptyList() // built from the offered options by the engine
        ReplyIntent.SCHEDULING -> if (sub == ReplySub.ASK_TIME) listOf(
            o(Stance.ASKBACK, listOf("What time works for you?", "What works best for you?"), listOf("What time would suit you?"), listOf("What time works for you?", "When works for you?")),
            o(Stance.DEFER, listOf("Let me check my schedule and get back to you", "Let me check and let you know"), listOf("Let me check my schedule and I will get back to you."), listOf("Let me check my calendar", "Lemme check and let you know")),
            o(Stance.OTHER, listOf("How about tomorrow?", "Maybe later this week?"), listOf("Would tomorrow suit you?"), listOf("How about tomorrow?", "Later this week maybe?")),
        ) else listOf(
            o(Stance.AFFIRM, listOf("That works for me", "Works for me", "Sounds good, that works"), listOf("That works for me, thank you."), listOf("Works for me!", "Yep, that works", "Perfect, works for me"), warm = true),
            o(Stance.DEFER, listOf("Let me check my calendar", "Let me check and confirm"), listOf("Let me check my calendar and confirm."), listOf("Let me check my calendar", "Lemme check and confirm")),
            o(Stance.DECLINE, listOf("Can we do another time?", "Could we do a different time?"), listOf("Could we find a different time?"), listOf("Can we do another time?", "Any chance we can do another time?")),
        )
        ReplyIntent.REQUEST -> request(sub)
        ReplyIntent.OPEN_QUESTION -> open(sub)
        ReplyIntent.CHECK_IN -> listOf(
            o(Stance.ASKBACK, listOf("Doing well, thanks! How about you?", "Good, thanks. How are you?"), listOf("I am well, thank you. How are you?"), listOf("Good, you?", "Pretty good, how about you?"), warm = true),
            o(Stance.ASKBACK, listOf("Pretty good, how are you?", "Not bad, how about you?"), listOf("Very well, thank you. And you?"), listOf("Not bad, you?", "All good, what's up with you?")),
            o(Stance.OTHER, listOf("Hanging in there, how about you?", "Busy, but good. How are you?"), listOf("Busy, but well. How are you?"), listOf("Hanging in there, you?", "Busy but good, hbu?")),
        )
        ReplyIntent.CLARIFY -> listOf(
            o(Stance.DEFER, listOf("Sorry, let me explain", "Sorry, that was unclear"), listOf("My apologies, let me explain."), listOf("Sorry, let me explain", "Oops, let me explain")),
            o(Stance.ASKBACK, listOf("Which part is unclear?", "What part didn't make sense?"), listOf("Which part is unclear?"), listOf("Which part?", "Which part didn't make sense?")),
            o(Stance.OTHER, listOf("Can I call you?", "Can I call you to explain?"), listOf("May I call you to explain?"), listOf("Can I call you?", "Mind if I call?")),
        )
        ReplyIntent.ETA_ASK -> listOf(
            o(Stance.ETA, listOf("On my way", "On my way!"), listOf("I am on my way."), listOf("On my way!", "omw")),
            o(Stance.ETA, listOf("Almost there", "Be there soon"), listOf("I will be there shortly."), listOf("Almost there!", "Be there in a sec")),
            o(Stance.ETA, listOf("Running a few minutes late", "Running a little late, sorry"), listOf("I am running a few minutes late."), listOf("Running a bit late, sorry", "Gonna be a few min late")),
        )
        ReplyIntent.ETA_UPDATE -> when (sub) {
            ReplySub.LATE -> listOf(
                o(Stance.NOWORRIES, listOf("No worries, see you soon", "No worries, take your time"), listOf("No problem, see you soon."), listOf("No worries!", "All good, see you soon")),
                o(Stance.THANKS, listOf("Thanks for letting me know", "Okay, thanks for the update"), listOf("Thank you for letting me know."), listOf("Thanks for the heads up", "Ok thanks for letting me know")),
                o(Stance.ACK, listOf("Take your time", "No rush"), listOf("Please take your time."), listOf("Take your time", "No rush at all")),
            )
            ReplySub.ARRIVED -> listOf(
                o(Stance.ETA, listOf("Great, I'll be right there", "Okay, coming now"), listOf("Wonderful, I will be right there."), listOf("Be right there!", "Coming!"), warm = true),
                o(Stance.ACK, listOf("Okay, see you in a sec", "Perfect, see you in a moment"), listOf("Perfect, see you shortly."), listOf("Ok see you in a sec", "Perfect!")),
                o(Stance.THANKS, listOf("Thanks, one minute", "Thanks, give me a minute"), listOf("Thank you, one moment please."), listOf("One sec!", "Give me a sec")),
            )
            else -> listOf(
                o(Stance.ACK, listOf("Sounds good, see you soon", "Great, see you soon"), listOf("Wonderful, see you soon."), listOf("Sounds good!", "Cool, see you soon"), warm = true),
                o(Stance.ACK, listOf("Okay, see you there", "Perfect, see you there"), listOf("Perfect, see you shortly."), listOf("Ok see you there", "Awesome, see you")),
                o(Stance.THANKS, listOf("Thanks for letting me know", "Thanks for the update"), listOf("Thank you for letting me know."), listOf("Thanks for the heads up", "Got it, thanks")),
            )
        }
        ReplyIntent.BAD_NEWS -> listOf(
            o(Stance.SYMPATHY, listOf("I'm so sorry to hear that", "I'm really sorry. That sounds so hard"), listOf("I am so sorry to hear that."), listOf("Ugh, I'm so sorry", "Oh no, I'm so sorry")),
            o(Stance.SYMPATHY, listOf("I'm here if you need anything", "Thinking of you. Let me know if I can help"), listOf("I am here if there is anything I can do."), listOf("I'm here if you need me", "Here for you, whatever you need")),
            o(Stance.ASKBACK, listOf("Do you want to talk about it?", "Can I call you?"), listOf("Would you like to talk about it?"), listOf("Want to talk about it?", "Can I call you?")),
        )
        ReplyIntent.GOOD_NEWS -> listOf(
            o(Stance.CONGRATS, listOf("Congratulations!", "Congrats, that's great news!"), listOf("Congratulations, that is wonderful news.", "Congratulations!"), listOf("Congrats!!", "Amazing, congrats!"), warm = true),
            o(Stance.CONGRATS, listOf("That's amazing news!", "That's wonderful!"), listOf("That is wonderful news."), listOf("That's awesome!", "No way, that's amazing!"), warm = true),
            o(Stance.CONGRATS, listOf("So happy for you!", "I'm so happy for you!"), listOf("I am delighted for you."), listOf("So happy for you!", "Yay, so happy for you!"), warm = true),
        )
        ReplyIntent.CONGRATS_RECEIVED -> listOf(
            o(Stance.THANKS, listOf("Thank you!", "Thank you so much!"), listOf("Thank you very much."), listOf("Thanks!", "Thank you!"), warm = true),
            o(Stance.THANKS, listOf("Thanks, I really appreciate it", "That means a lot, thank you"), listOf("Thank you, I appreciate it."), listOf("Thanks, means a lot", "Aw thanks, that means a lot"), warm = true),
            o(Stance.THANKS, listOf("Thanks for the kind words", "Thank you, that's kind of you"), listOf("Thank you, that is kind of you."), listOf("Thanks so much!", "Thanks, you're the best")),
        )
        ReplyIntent.APOLOGY -> listOf(
            o(Stance.NOWORRIES, listOf("No worries", "No worries at all"), listOf("No need to apologise."), listOf("No worries!", "All good")),
            o(Stance.NOWORRIES, listOf("It's okay, no problem", "That's okay, don't worry about it"), listOf("That is quite all right."), listOf("It's all good", "Don't worry about it")),
            o(Stance.THANKS, listOf("Thanks for letting me know", "Thanks for telling me"), listOf("Thank you for letting me know."), listOf("Thanks for the heads up", "Thanks for telling me")),
        )
        ReplyIntent.THANKS -> listOf(
            o(Stance.WELCOME, listOf("You're welcome!", "You're welcome"), listOf("You are welcome.", "My pleasure."), listOf("Anytime!", "No problem!"), warm = true),
            o(Stance.WELCOME, listOf("No problem", "Anytime"), listOf("Happy to help."), listOf("np", "No worries!")),
            o(Stance.WELCOME, listOf("Happy to help", "Glad I could help"), listOf("Glad I could help."), listOf("Happy to help!", "Of course!")),
        )
        ReplyIntent.AFFECTION -> listOf(
            o(Stance.LOVE, listOf("Love you too", "Love you too!"), listOf("Love you too"), listOf("Love you too!", "Love you more"), warm = true),
            o(Stance.LOVE, listOf("Miss you too", "I miss you too"), listOf("I miss you too"), listOf("Miss you too!", "Miss you more"), warm = true),
            o(Stance.LOVE, listOf("Thinking of you too", "Thinking about you too"), listOf("Thinking of you too"), listOf("Thinking of you too!", "You're the best"), warm = true),
        )
        ReplyIntent.SHARE -> when (sub) {
            ReplySub.PHOTO -> listOf(
                o(Stance.CONGRATS, listOf("Nice!", "Great photo!"), listOf("Lovely, thank you for sharing.", "That is a lovely photo."), listOf("Love it!", "So good!", "Nice one!"), warm = true),
                o(Stance.THANKS, listOf("Thanks for sending this", "Thanks for sharing"), listOf("Thank you for sending this."), listOf("Thanks for sending!", "Thanks for sharing!")),
                o(Stance.ACK, listOf("Got it", "Got it, thanks"), listOf("Received, thank you."), listOf("Got it!", "Love this")),
            )
            ReplySub.LOCATION -> listOf(
                o(Stance.ACK, listOf("Got it, thanks", "Got it, thank you"), listOf("Received, thank you."), listOf("Got it!", "Perfect, got it")),
                o(Stance.ACK, listOf("Thanks, see you there", "Perfect, see you there"), listOf("Thank you, see you there."), listOf("Thanks, see you there!", "Great, see you there")),
                o(Stance.THANKS, listOf("Thanks for sharing", "Thanks for sending"), listOf("Thank you for sharing this."), listOf("Thanks for sharing", "Thanks!")),
            )
            else -> listOf(
                o(Stance.THANKS, listOf("Thanks for sharing", "Thanks for sending this"), listOf("Thank you for sharing this."), listOf("Thanks for sharing!", "Thanks for the share")),
                o(Stance.ACK, listOf("Got it, thanks", "Got it"), listOf("Received, thank you."), listOf("Got it!", "Nice, got it")),
                o(Stance.DEFER, listOf("Thanks, I'll take a look", "I'll take a look"), listOf("Thank you, I will take a look."), listOf("Thanks, will check it out", "Cool, I'll check it out")),
            )
        }
        ReplyIntent.REMINDER -> if (sub == ReplySub.CONFIRM) listOf(
            o(Stance.AFFIRM, listOf("Confirmed", "Confirmed, see you then"), listOf("Confirmed, thank you."), listOf("Confirmed", "Yep, confirmed")),
            o(Stance.DECLINE, listOf("I need to reschedule", "Can we reschedule?"), listOf("I would like to reschedule, please."), listOf("I need to reschedule", "Can we reschedule?")),
            o(Stance.THANKS, listOf("Thank you", "Thanks for the reminder"), listOf("Thank you for the reminder."), listOf("Thanks!", "Thanks for the reminder")),
        ) else listOf(
            o(Stance.THANKS, listOf("Thanks for the reminder", "Thanks for reminding me"), listOf("Thank you for the reminder."), listOf("Thanks for the reminder!", "Thanks for the heads up")),
            o(Stance.AFFIRM, listOf("I'll be there", "Got it, I'll be there"), listOf("I will be there."), listOf("I'll be there!", "Yep, I'll be there")),
            o(Stance.DECLINE, listOf("Can we reschedule?", "I may need to reschedule"), listOf("May we reschedule?"), listOf("Can we reschedule?", "I might need to move it")),
        )
        ReplyIntent.INFO -> listOf(
            o(Stance.THANKS, listOf("Thanks for letting me know", "Thanks for the update"), listOf("Thank you for letting me know."), listOf("Thanks for the heads up", "Thanks for letting me know")),
            o(Stance.ACK, listOf("Got it, thanks", "Okay, got it"), listOf("Understood, thank you."), listOf("Got it", "Ok thanks")),
            o(Stance.ACK, listOf("Okay, noted", "Noted, thanks"), listOf("Noted, thank you."), listOf("Noted!", "Okay, cool")),
        )
        ReplyIntent.GREETING -> when (sub) {
            ReplySub.MORNING -> listOf(
                o(Stance.GREET, listOf("Good morning!", "Morning!"), listOf("Good morning."), listOf("Morning!", "Good morning!"), warm = true),
                o(Stance.ASKBACK, listOf("Good morning, how are you?", "Morning! How are you?"), listOf("Good morning, how are you?"), listOf("Morning, how's it going?", "Morning! What's up?")),
            )
            ReplySub.AFTERNOON, ReplySub.EVENING -> listOf(
                o(Stance.GREET, listOf("Hi there!", "Hello!"), listOf("Hello."), listOf("Hey!", "Hi!"), warm = true),
                o(Stance.ASKBACK, listOf("Hi, how are you?", "Hello, how are you?"), listOf("Hello, how are you?"), listOf("Hey, how's it going?", "Hey, what's up?")),
            )
            else -> listOf(
                o(Stance.GREET, listOf("Hi!", "Hey!"), listOf("Hello."), listOf("Hey!", "Hey there!"), warm = true),
                o(Stance.ASKBACK, listOf("Hi, how are you?", "Hey, how's it going?"), listOf("Hello, how are you?"), listOf("Hey, what's up?", "Hey, how's it going?")),
                o(Stance.ASKBACK, listOf("Hey, what's up?", "Hi, what's going on?"), listOf("Hello, how may I help?"), listOf("Hey, what's up?", "Yo, what's up?")),
            )
        }
        ReplyIntent.FAREWELL -> if (sub == ReplySub.NIGHT) listOf(
            o(Stance.BYE, listOf("Good night!", "Night!"), listOf("Good night."), listOf("Night!", "Goodnight!"), warm = true),
            o(Stance.BYE, listOf("Sleep well", "Sleep well!"), listOf("Sleep well."), listOf("Sleep well!", "Sweet dreams")),
            o(Stance.BYE, listOf("Talk tomorrow", "Talk to you tomorrow"), listOf("Speak tomorrow."), listOf("Talk tomorrow!", "ttyl")),
        ) else listOf(
            o(Stance.BYE, listOf("Talk soon", "Talk to you soon"), listOf("Speak soon."), listOf("Talk soon!", "ttyl")),
            o(Stance.BYE, listOf("Take care", "Take care!"), listOf("Take care."), listOf("Take care!", "Later!")),
            o(Stance.BYE, listOf("Bye!", "See you!"), listOf("Goodbye."), listOf("Bye!", "Cya!")),
        )
        ReplyIntent.ACK, ReplyIntent.REACTION, ReplyIntent.STATEMENT, ReplyIntent.EMPTY -> emptyList()
    }

    private fun yesNo(sub: ReplySub): List<Opt> = when (sub) {
        ReplySub.AVAILABILITY -> listOf(
            o(Stance.AFFIRM, listOf("Yes, I'm free", "Yes, I'm available", "Yes, I can do that"), listOf("Yes, I am available."), listOf("Yeah, I'm free", "Yep, I'm around", "Yeah I'm free"), warm = true),
            o(Stance.DECLINE, listOf("Sorry, I'm busy then", "No, I'm not free then"), listOf("I am afraid I am not available."), listOf("Can't, I'm busy then", "Not free then, sorry")),
            o(Stance.DEFER, listOf("Let me check my schedule", "Let me check and get back to you"), listOf("Let me check my schedule and get back to you."), listOf("Let me check my calendar", "Lemme check")),
        )
        ReplySub.DID_YOU -> listOf(
            o(Stance.AFFIRM, listOf("Yes, I did", "Yes, I have"), listOf("Yes, I have."), listOf("Yep, I did", "Yeah I did")),
            o(Stance.DECLINE, listOf("Not yet", "No, not yet"), listOf("Not yet, I am afraid."), listOf("Not yet", "Nope, not yet")),
            o(Stance.DEFER, listOf("Let me check", "Let me check and get back to you"), listOf("Let me check and get back to you."), listOf("Lemme check", "Let me check")),
        )
        ReplySub.ARE_YOU_OK -> listOf(
            o(Stance.AFFIRM, listOf("Yes, I'm fine, thanks", "I'm okay, thanks for checking"), listOf("Yes, I am fine, thank you for asking."), listOf("Yeah I'm good, thanks!", "I'm good, thanks for checking"), warm = true),
            o(Stance.DECLINE, listOf("Not really, can we talk?", "Not great, can I call you?"), listOf("Not really. May I call you?"), listOf("Not really, can I call you?", "Kinda rough, can we talk?")),
            o(Stance.THANKS, listOf("Thanks for checking in", "Thanks for asking"), listOf("Thank you for checking in."), listOf("Thanks for checking in", "Thanks for asking")),
        )
        ReplySub.WANT_TO -> listOf(
            o(Stance.AFFIRM, listOf("Yes, I'd love to", "Yes, sounds fun", "Sure, I'm in"), listOf("Yes, I would love to."), listOf("I'm in!", "Yeah, I'm down", "Sounds fun, I'm in"), warm = true),
            o(Stance.DECLINE, listOf("Thanks, but I can't make it", "I'll pass this time, thanks"), listOf("Thank you for the invitation, but I am unable to attend."), listOf("Can't make it, sorry", "I'll pass this time, thanks")),
            o(Stance.DEFER, listOf("Let me check and let you know", "Let me see if I can"), listOf("Let me check and get back to you."), listOf("Let me check and let you know", "Lemme see if I can")),
        )
        ReplySub.OPINION -> listOf(
            o(Stance.AFFIRM, listOf("Sounds good to me", "Works for me"), listOf("That sounds good to me."), listOf("Sounds good!", "Works for me!", "Looks good to me"), warm = true),
            o(Stance.DECLINE, listOf("I'd prefer something else", "Not sure about that"), listOf("I would prefer an alternative."), listOf("Hmm, not sure about that", "I'd rather do something else")),
            o(Stance.DEFER, listOf("Let me think about it", "Let me think and get back to you"), listOf("Let me think about it."), listOf("Let me think about it", "Lemme think on it")),
        )
        else -> listOf(
            o(Stance.AFFIRM, listOf("Yes", "Yes, sounds good", "Yes, that works"), listOf("Yes, that works for me.", "Yes."), listOf("Yeah, sounds good", "Yep!", "Sure thing"), warm = true),
            o(Stance.DECLINE, listOf("No, sorry", "Not this time, sorry"), listOf("I am afraid not."), listOf("Nope, sorry", "Can't, sorry", "Not today, sorry")),
            o(Stance.DEFER, listOf("Let me check and get back to you", "Let me find out and let you know"), listOf("Let me check and get back to you."), listOf("Let me check and I'll let you know", "Lemme check")),
        )
    }

    private fun request(sub: ReplySub): List<Opt> = when (sub) {
        ReplySub.CALL -> listOf(
            o(Stance.AFFIRM, listOf("I'll call you in a bit", "Calling you shortly"), listOf("I will call you shortly."), listOf("I'll call you in a bit", "Calling you in a few")),
            o(Stance.DECLINE, listOf("Can't talk right now, can I text?", "I can't talk right now, can we text?"), listOf("I cannot talk at the moment. May we text?"), listOf("Can't talk now, text me?", "Can't talk right now, can I call later?")),
            o(Stance.ASKBACK, listOf("Is everything okay?", "What's up?"), listOf("Is everything all right?"), listOf("Everything ok?", "What's up?")),
        )
        ReplySub.LET_ME_KNOW -> listOf(
            o(Stance.AFFIRM, listOf("Will do", "Sure, I'll let you know"), listOf("I will let you know."), listOf("Will do!", "Yep, will do")),
            o(Stance.ACK, listOf("Sounds good", "Okay, sounds good"), listOf("Understood."), listOf("Sounds good", "Ok cool")),
            o(Stance.DEFER, listOf("I'll get back to you soon", "Let me check and let you know"), listOf("I will get back to you soon."), listOf("Will let you know soon", "I'll let you know soon")),
        )
        ReplySub.HELP -> listOf(
            o(Stance.ASKBACK, listOf("Sure, what do you need?", "Of course, what's up?"), listOf("Of course. What do you need?"), listOf("Sure, what's up?", "Yeah, what do you need?")),
            o(Stance.DEFER, listOf("Can it wait a little?", "Let me see what I can do"), listOf("Let me see what I can do."), listOf("Can it wait a bit?", "Lemme see what I can do")),
            o(Stance.DECLINE, listOf("Sorry, I can't right now", "I can't right now, sorry"), listOf("I am afraid I cannot right now."), listOf("Can't right now, sorry", "Sorry, can't at the moment")),
        )
        // Money, gift cards and bank details: never a one-tap "yes".
        ReplySub.MONEY -> listOf(
            o(Stance.DEFER, listOf("Let me check and get back to you", "Let me look into it"), listOf("Let me look into it and get back to you."), listOf("Let me check and get back to you", "Lemme look into it")),
            o(Stance.ASKBACK, listOf("Can I call you first?", "Can we talk on the phone first?"), listOf("May I call you first?"), listOf("Can I call you first?", "Let me call you first")),
            o(Stance.DECLINE, listOf("Sorry, I can't right now", "I can't help with that right now"), listOf("I am afraid I cannot help with that."), listOf("Can't right now, sorry", "Sorry, not right now")),
        )
        else -> listOf(
            o(Stance.AFFIRM, listOf("Sure, I can do that", "Sure, no problem", "Of course, I'll take care of it"), listOf("Certainly, I will take care of it.", "Of course."), listOf("Sure thing", "On it!", "Yep, will do"), warm = true),
            o(Stance.DEFER, listOf("Let me see what I can do", "Let me check and get back to you"), listOf("Let me see what I can do."), listOf("Lemme see what I can do", "Let me check")),
            o(Stance.DECLINE, listOf("Sorry, I can't right now", "Sorry, I'm not able to right now"), listOf("I am afraid I cannot at the moment."), listOf("Can't right now, sorry", "Sorry, can't right now")),
        )
    }

    private fun open(sub: ReplySub): List<Opt> = when (sub) {
        ReplySub.WHEN -> listOf(
            o(Stance.DEFER, listOf("Let me check and get back to you", "I'll check and let you know"), listOf("Let me check and get back to you."), listOf("Let me check and let you know", "Lemme check")),
            o(Stance.ASKBACK, listOf("What time works for you?", "When works best for you?"), listOf("What time would suit you?"), listOf("What time works for you?", "When works for you?")),
            o(Stance.OTHER, listOf("Can I confirm in a bit?", "I'll confirm soon"), listOf("May I confirm shortly?"), listOf("Can I confirm in a bit?", "Will confirm soon")),
        )
        ReplySub.WHERE -> listOf(
            o(Stance.DEFER, listOf("Let me check and let you know", "I'll check and get back to you"), listOf("Let me check and get back to you."), listOf("Let me check and let you know", "Lemme check")),
            o(Stance.ASKBACK, listOf("Where works best for you?", "What do you prefer?"), listOf("Where would suit you best?"), listOf("Where works for you?", "Where do you want to go?")),
            o(Stance.OTHER, listOf("Wherever is easiest for you", "I'm flexible, you pick"), listOf("Wherever is most convenient for you."), listOf("Wherever works for you", "You pick!")),
        )
        ReplySub.WHY -> listOf(
            o(Stance.DEFER, listOf("Let me explain in a bit", "It's a long story, can I call you?"), listOf("Let me explain shortly."), listOf("Long story, let me explain later", "It's a long story")),
            o(Stance.ASKBACK, listOf("Can I call you to explain?", "Can we talk about it?"), listOf("May I call you to explain?"), listOf("Can I call you?", "Mind if I call?")),
            o(Stance.OTHER, listOf("Good question, let me think", "Let me get back to you on that"), listOf("A fair question. Let me get back to you."), listOf("Good question, lemme think", "Let me get back to you on that")),
        )
        ReplySub.OPINION -> listOf(
            o(Stance.AFFIRM, listOf("I think it sounds good", "I like it"), listOf("I think that sounds good."), listOf("Sounds good to me", "I like it!"), warm = true),
            o(Stance.DEFER, listOf("Let me think about it", "Let me think and get back to you"), listOf("Let me think about it."), listOf("Let me think about it", "Lemme think on it")),
            o(Stance.ASKBACK, listOf("What do you think?", "What are you leaning towards?"), listOf("What are your thoughts?"), listOf("What do you think?", "What are you thinking?")),
        )
        ReplySub.TEASER -> listOf(
            o(Stance.ASKBACK, listOf("Tell me!", "What is it?"), listOf("Please, tell me."), listOf("Tell me!", "What?! Tell me!")),
            o(Stance.ASKBACK, listOf("What happened?", "Okay, I'm listening"), listOf("I am listening."), listOf("Spill!", "What's up?")),
            o(Stance.OTHER, listOf("Now I'm curious", "You've got my attention"), listOf("You have my attention."), listOf("Now I'm curious", "Ooh, go on")),
        )
        else -> listOf(
            o(Stance.DEFER, listOf("Let me get back to you on that", "Good question, let me check"), listOf("Let me get back to you on that."), listOf("Let me get back to you on that", "Good question, lemme check")),
            o(Stance.ASKBACK, listOf("Can I call you in a bit?", "Can we talk about it later?"), listOf("May I call you shortly?"), listOf("Can I call you in a bit?", "Call you in a bit?")),
            o(Stance.OTHER, listOf("Let me think about it", "I'll look into it"), listOf("I will look into it."), listOf("Lemme think about it", "I'll look into it")),
        )
    }
}
