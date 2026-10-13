#!/usr/bin/env python3
"""Generates the synthetic smart-reply corpus: app/src/test/resources/smartreply/corpus.tsv

Everything is obviously fake: 555-01xx numbers, example names, invented content. Deterministic (no randomness):
re-running produces the same file. The hand-written, free-form set (challenge.tsv) is NOT generated.

Columns (tab separated):
  id, intent, expect, tone, address, label, known, persona, thread
  expect  REPLY    a person is waiting: must get chips, and the Needs a reply mark
          OPTIONAL a courtesy reply may be offered; no Needs a reply mark
          NONE     nothing may be suggested and no mark shown
  intent  the ReplyIntent the message should be read as (NONE when it must be suppressed)
  thread  messages oldest first, joined by " ||| ", each starting "< " (incoming) or "> " (sent by the user);
          "< [img]" is an incoming picture with no text.
"""
import os

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "test", "resources", "smartreply", "corpus.tsv")

PEOPLE = ["+14155550101", "+14155550102", "+14155550103", "+14155550104", "+14155550105", "+14155550106", "+14155550107", "+14155550108"]
INTL = "+447700900123"

# persona -> (earlier exchange, ending with the user's own message). The incoming filler is never "waiting".
PERSONAS = {
    "none": [],
    "casual_emoji": ["< anyone up for food later", "> yeah i'm in \U0001F602", "< cool", "> sounds good \U0001F44D see you there", "< lol ok", "> haha yep \U0001F642"],
    "casual_plain": ["< you around later", "> yeah sounds good", "< cool", "> ok see you then", "< lol", "> nope can't make it, thanks man"],
    "formal": ["< Good afternoon, do you have the report?", "> Hello, thank you for your message. I will send it this evening.", "< Thank you.", "> You are welcome. I will confirm shortly."],
    "neutral": ["< Are we still on?", "> Yes, that works for me.", "< Great", "> Thanks for letting me know."],
}
PERSONA_ORDER = ["none", "casual_plain", "casual_emoji", "formal", "neutral", "none", "casual_plain", "neutral"]

# (intent, tone, [messages])  -> expect REPLY unless noted
REPLY = {
    "YES_NO": [
        ("casual", ["Are you free tonight?", "u free later?", "Do you want to grab dinner?", "wanna come to the game on Saturday?", "Did you see my email?", "Have you eaten yet?",
                    "are you ok?", "Are you coming?", "did you get the package?", "You home?", "Are you up for a walk?", "Is it okay if I bring my cousin?", "sounds good?", "Can I borrow your charger tomorrow?"]),
        ("formal", ["Are you available for a call this afternoon?", "Would you like to join us for lunch on Friday?", "Have you had a chance to review the document?", "Is it acceptable if I send the invoice on Monday?",
                    "Do you have a moment to discuss the schedule?", "Are you able to attend the meeting?"]),
    ],
    "OPEN_QUESTION": [
        ("casual", ["what do you think about the new place?", "where should we eat?", "what time is the thing again?", "why didn't you answer?", "how did the interview go?", "what's the plan for tomorrow?",
                    "who's coming Saturday?", "thoughts?", "guess what", "which one do you like better?"]),
        ("formal", ["What are your thoughts on the proposal?", "When would be a suitable time to meet?", "Where would you like to hold the meeting?", "Could you tell me what the next steps are?"]),
    ],
    "EITHER_OR": [
        ("casual", ["pizza or tacos?", "Saturday or Sunday?", "do you want coffee or tea?", "call or text you later?", "beach or mountains?", "do you prefer morning or evening?"]),
        ("formal", ["Would you prefer Tuesday or Thursday?", "Shall we use email or phone for the update?"]),
    ],
    "SCHEDULING": [
        ("casual", ["Does 7pm work?", "how about Friday evening?", "let's meet at 6 by the cafe", "can we reschedule to next week?", "What about tomorrow at noon?", "when are you free this week?", "what time works for you?", "Can you make it at 5?"]),
        ("formal", ["Does Wednesday at 10:00 suit you?", "Would 3pm on Thursday work for you?", "When are you available for a short meeting?", "Can we move our appointment to the afternoon?"]),
    ],
    "REQUEST": [
        ("casual", ["can you pick up milk on your way home?", "could you send me that photo from last night", "can you call me when you get a sec", "need your help with something", "please let me know when you land", "Can you water the plants this weekend?",
                    "hit me up when you're out", "can you grab my package from the front desk", "mind sending me the address?", "do me a favor and text my mom"]),
        ("formal", ["Could you please send me the updated figures?", "Would you mind reviewing the draft before noon?", "Please let me know once you have confirmed.", "Can you forward the agenda to the team?"]),
    ],
    "CHECK_IN": [
        ("casual", ["hey how are you?", "how's it going", "what's up?", "how was your day?", "how have you been", "hey, how was your weekend?"]),
        ("formal", ["Hello, how are you?", "I hope you are well. How have you been?"]),
    ],
    "ETA_ASK": [
        ("casual", ["where are you?", "how far are you", "are you on your way?", "where u at", "what's your eta?"]),
        ("formal", ["Where are you at the moment?", "When do you expect to arrive?"]),
    ],
    "CLARIFY": [("casual", ["what?", "huh?", "??", "wdym", "what do you mean?"])],
    "BAD_NEWS": [
        ("casual", ["my dad passed away this morning", "I got laid off today", "bad news, my dog is really sick", "I failed my exam", "we broke up", "I'm in the hospital, it's nothing serious but I'm scared", "i lost my job"]),
        ("formal", ["I regret to inform you that my grandmother passed away.", "Unfortunately I was in a car accident this morning."]),
    ],
    "GOOD_NEWS": [
        ("casual", ["I got the job!!", "we're engaged!", "I passed my driving test", "guess who got promoted", "we're having a baby", "I got into grad school!", "great news, the house sold", "I did it, I finished the marathon"]),
        ("formal", ["I am pleased to share that I have been promoted.", "Good news: we signed the lease today."]),
    ],
    "REMINDER": [
        ("casual", ["don't forget dinner tomorrow at 7", "reminder: soccer practice is at 5pm today", "just a reminder about the dentist on Thursday at 3pm", "remember we have the school thing tomorrow at 9"]),
        ("formal", ["This is a reminder of your appointment on Thursday at 2:30 PM. Please reply C to confirm.", "Friendly reminder: your table is booked for Friday at 8pm. Please confirm."]),
    ],
}
# courtesy replies: chips allowed, no Needs a reply mark
OPTIONAL = {
    "THANKS": [("casual", ["thanks!", "thank you so much", "thx a lot man", "you're the best, thanks", "appreciate it"]), ("formal", ["Thank you for your help.", "Many thanks for sending that over."])],
    "APOLOGY": [("casual", ["sorry about last night", "my bad, I forgot", "sorry I missed your call"]), ("formal", ["I apologise for the delay in replying.", "Apologies for the confusion yesterday."])],
    "AFFECTION": [("casual", ["love you", "miss you", "thinking of you today"])],
    "ETA_UPDATE": [("casual", ["on my way", "running late, sorry", "omw", "just got here, I'm outside", "stuck in traffic, 10 more min", "be there in 5"]), ("formal", ["I am on my way and will arrive shortly.", "I am running a few minutes late."])],
    "CONGRATS_RECEIVED": [("casual", ["congrats on the new place!", "congratulations!!", "so proud of you"]), ("formal", ["Congratulations on your promotion.", "Well done on the presentation."])],
    "FAREWELL": [("casual", ["good night", "ttyl", "talk later", "gn!", "bye for now"]), ("formal", ["Have a good evening.", "Take care, speak soon."])],
    "INFO": [("casual", ["fyi the meeting got moved to 4", "heads up, the restaurant is closed tomorrow", "just so you know I got home safe", "the game is postponed till next week"]), ("formal", ["Please note that the office will be closed on Monday.", "I wanted to let you know the shipment was delayed."])],
    "SHARE": [("casual", ["check this out https://example.com/funny-video", "here's my location https://maps.app.goo.gl/AbCd123", "look at this https://example.org/article", "sent you the photos"]), ("formal", ["Please find the link to the document: https://example.com/doc"])],
    "GREETING": [("casual", ["hey", "hi!", "good morning", "hello there", "yo"]), ("formal", ["Good afternoon.", "Hello."])],
}
# must get nothing: (category, tone, address-kind, label, text)
NONE_PERSON = {
    "ACK": ["ok", "k", "lol", "haha", "sounds good", "got it", "👍", "cool", "yep", "ok thanks", "no worries", "awesome", "see you then", "same"],
    "REACTION": ['Liked "see you at 7"', 'Laughed at "that was hilarious"', "😂", "❤️", "🙏🙏", 'Loved "on my way"'],
    "STATEMENT": ["I'll be at the office until 5.", "The weather is terrible today.", "Just finished my workout.", "Dinner was great last night.", "I think the train was delayed again.", "It's raining so hard here."],
    "AUTO_GENERATED": ["This is an automated message. Please do not reply.", "Auto-reply: I am out of office until Monday.", "I'm currently driving and will reply when I can.", "Automatic reply: I am busy right now and will reply later."],
    "ASKS_FOR_CODE": ["hey can you send me the code you just got?", "please read me the verification code on your phone", "share the 6 digit code Google sent you", "give me the PIN you got by text"],
}
JUNK = [
    # (category, address, label, text, known)
    ("OTP", "32665", "OTP_2FA", "Your Example Bank verification code is 482913. It expires in 10 minutes. Never share it."),
    ("OTP", "+14155550141", "OTP_2FA", "123456 is your Example login code. Do not share it."),
    ("OTP", "EXAMPLE", "PERSONAL", "G-482913 is your Example verification code."),
    ("OTP", "+14155550142", "PERSONAL", "Your security code is 771203. Enter it to sign in. Can you confirm it was you?"),
    ("OTP", "+14155550143", "UNLABELED", "Use code 904512 to verify your account. Valid for 5 minutes."),
    ("PROMO", "88022", "PROMO", "FLASH SALE: 40% off everything today only! Reply STOP to opt out."),
    ("PROMO", "+14155550144", "PROMO", "Hi from Example Pizza! Get a free large pizza with any order. Use code PIZZA20. Reply STOP to unsubscribe."),
    ("PROMO", "+14155550145", "PERSONAL", "Exclusive offer just for you: 50% off all shoes this weekend only. Shop now at example.com/shoes. Msg&data rates may apply."),
    ("PROMO", "EXAMPLESHOP", "UNLABELED", "Your cart misses you! Come back and save 15%. Are you still interested?"),
    ("SPAM", "+14155550146", "SPAM", "You have been selected for a free cruise. Call now to claim. Reply STOP to end."),
    ("SPAM", "+14155550147", "SPAM", "Do you want to lower your credit card interest rate? Reply YES to learn more."),
    ("SCAM", "+14155550148", "SCAM", "Your package is held. Pay the 1.99 fee at usps-redelivery.example to release it. Can you confirm your address?"),
    ("SCAM", "+14155550149", "PERSONAL", "Unpaid toll balance of $4.15. Pay now at example-tolls.top to avoid a late fee. Are you the owner of this vehicle?"),
    ("SCAM", "+14155550150", "PERSONAL", "Hi, is this Sarah? I found your number from a friend. Can we be friends?"),
    ("SCAM", "+14155550151", "UNLABELED", "Congratulations, you have won a $500 gift card! Claim your prize now: example-prize.top"),
    ("SCAM", "+14155550152", "SCAM", "Your account has been suspended. Verify your identity at example-secure.top immediately. Can you do this today?"),
    ("SCAM", "+14155550153", "PERSONAL", "Hi mom, this is my new number, my phone broke. Can you send me $200 for a gift card? Don't call."),
    ("SHORTCODE", "55123", "PERSONAL", "Your appointment is tomorrow at 3pm. Reply C to confirm or R to reschedule."),
    ("SHORTCODE", "227898", "UNLABELED", "Are you coming to the event on Friday?"),
    ("ALPHA", "EXAMPLEBANK", "PERSONAL", "Did you make a purchase of $42.10 at EXAMPLE STORE? Reply YES or NO."),
    ("ALPHA", "AMAZONX", "UNLABELED", "Your order has shipped. Can you confirm someone will be home?"),
    ("TOLLFREE", "+18005550142", "PERSONAL", "This is Example Clinic. Can you confirm your appointment tomorrow at 9am?"),
    ("TOLLFREE", "+18665550143", "UNLABELED", "How would you rate your visit today? Reply 1-5."),
    ("GROUP", "+14155550101,+14155550102", "PERSONAL", "Are you guys free Saturday?"),
    ("GROUP", "+14155550101;+14155550103;+14155550104", "PERSONAL", "Who is bringing snacks?"),
    ("EMAIL", "friend@example.com", "PERSONAL", "hey are you around later?"),
    ("MMS_PLACEHOLDER", "+14155550101", "PERSONAL", "[MMS message]"),
    ("MMS_PLACEHOLDER", "+14155550102", "PERSONAL", ""),
    ("STRANGER_LINK", "+14155550154", "PERSONAL", "Hey, check this out https://example-videos.top/watch?v=1"),
    ("STRANGER_PROBE", "+14155550155", "PERSONAL", "Who is this? I think I have the wrong number."),
    ("STRANGER_PROBE", "+14155550156", "PERSONAL", "hi, do you remember me from the conference?"),
    ("PHISH", "+14155550157", "PERSONAL", "Your Example account is locked. Verify now: example-login.top. Are you there?"),
    ("OPTOUT", "+14155550158", "PERSONAL", "Example Gym: your membership renews soon. Want to freeze it? Text STOP to cancel."),
    ("OPTOUT", "+14155550159", "WORK", "Example HR: Are you attending Friday's all-hands? Reply STOP to unsubscribe from alerts."),
]
# user's own message is the latest
USER_LAST = [
    ["< are you free tonight?", "> yes, what time?"],
    ["< can you pick up milk?", "> sure thing"],
    ["< how are you?", "> good, you?"],
    ["< dinner?", "> sounds good"],
    ["< call me", "> calling now"],
]

rows = []
n = 0
def add(intent, expect, tone, addr, label, known, persona, thread):
    global n
    n += 1
    rows.append(("s%03d" % n, intent, expect, tone, addr, label, known, persona, thread))

def history(persona):
    return PERSONAS[persona]

def build(persona, last_lines):
    return " ||| ".join(history(persona) + last_lines)

i = 0
for intent, groups in REPLY.items():
    for tone, msgs in groups:
        for m in msgs:
            persona = PERSONA_ORDER[i % len(PERSONA_ORDER)]
            if tone == "formal" and persona in ("casual_plain", "casual_emoji"):
                persona = "formal"
            addr = PEOPLE[i % len(PEOPLE)]
            label = "WORK" if tone == "formal" else "PERSONAL"
            known = 1 if i % 3 == 0 else 0
            # A first-time sender (no history, not saved) can still ask a plain question.
            add(intent, "REPLY", tone, addr, label, known, persona, build(persona, ["< " + m]))
            i += 1
# a few that arrive in a burst: a question followed by a filler text
for q, filler in [("are you free tonight?", "hello??"), ("can you call me when you get a chance", "ok?"), ("what time should I come over", "lol"), ("did you get the tickets?", "👍")]:
    add({"are you free tonight?": "YES_NO", "can you call me when you get a chance": "REQUEST", "what time should I come over": "SCHEDULING", "did you get the tickets?": "YES_NO"}[q],
        "REPLY", "casual", PEOPLE[i % len(PEOPLE)], "PERSONAL", 1, "casual_plain", build("casual_plain", ["< " + q, "< " + filler]))
    i += 1
# a greeting that turns into a question
add("CHECK_IN", "REPLY", "casual", PEOPLE[1], "PERSONAL", 1, "none", "< hey ||| < how are you doing?")
add("YES_NO", "REPLY", "casual", PEOPLE[2], "PERSONAL", 1, "none", "< hey! ||| < are you free to talk?")
# intl number the user knows
add("YES_NO", "REPLY", "casual", INTL, "PERSONAL", 1, "none", "< Are you coming to the wedding?")
add("SCHEDULING", "REPLY", "formal", INTL, "WORK", 1, "formal", build("formal", ["< Does Tuesday at 11 work for you?"]))
# money asks from a known contact are still replies, but never with a "yes"
add("REQUEST", "REPLY", "casual", PEOPLE[3], "PERSONAL", 1, "casual_plain", build("casual_plain", ["< can you venmo me $20 for dinner?"]))
add("REQUEST", "REPLY", "casual", PEOPLE[4], "PERSONAL", 1, "none", "< could you lend me some money until Friday?")
# image from a known contact
add("SHARE", "OPTIONAL", "casual", PEOPLE[5], "PERSONAL", 1, "casual_emoji", build("casual_emoji", ["< [img]"]))
add("SHARE", "OPTIONAL", "casual", PEOPLE[6], "PERSONAL", 1, "none", "> look at the sunset ||| < [img]")

for intent, groups in OPTIONAL.items():
    for tone, msgs in groups:
        for m in msgs:
            persona = PERSONA_ORDER[i % len(PERSONA_ORDER)]
            if tone == "formal":
                persona = "formal"
            label = "WORK" if tone == "formal" else "PERSONAL"
            add(intent, "OPTIONAL", tone, PEOPLE[i % len(PEOPLE)], label, 1, persona, build(persona, ["< " + m]))
            i += 1

for cat, msgs in NONE_PERSON.items():
    for m in msgs:
        persona = PERSONA_ORDER[i % len(PERSONA_ORDER)]
        intent = "NONE" if cat in ("AUTO_GENERATED", "ASKS_FOR_CODE") else cat
        add(intent, "NONE", "casual", PEOPLE[i % len(PEOPLE)], "PERSONAL", 1, persona, build(persona, ["< " + m]))
        i += 1

for cat, addr, label, text in JUNK:
    thread = "< [blank]" if (cat == "MMS_PLACEHOLDER" and text == "") else "< " + text
    add("NONE", "NONE", "casual", addr, label, 0, "none", thread)
for t in USER_LAST:
    add("NONE", "NONE", "casual", PEOPLE[i % len(PEOPLE)], "PERSONAL", 1, "none", " ||| ".join(t))
    i += 1
# an MMS with no text and no picture
add("NONE", "NONE", "casual", PEOPLE[0], "PERSONAL", 1, "none", "< [mms]")
# an image from a stranger
add("NONE", "NONE", "casual", "+14155550160", "PERSONAL", 0, "none", "< [img]")

with open(OUT, "w", encoding="utf-8") as f:
    f.write("id\tintent\texpect\ttone\taddress\tlabel\tknown\tpersona\tthread\n")
    for r in rows:
        f.write("\t".join(str(x) for x in r) + "\n")
print(len(rows), "conversations ->", os.path.relpath(OUT))
