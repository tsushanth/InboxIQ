#!/usr/bin/env python3
"""Deterministic generator for the SYNTHETIC InboxIQ classification corpus.

Everything produced here is fake: phone numbers are 555-01xx, domains are
example.* or obviously made-up, businesses are fictional (a few real brand
names appear ONLY inside clearly fake smishing text, because that is how
smishing really reads). No real user data was read or used.

Output (UTF-8 TSV, one thread per line):
  id  category  label  address  replies  bodies
`bodies` is the incoming messages, NEWEST FIRST, joined by ' ||| '.
`replies` is the number of the user's own outgoing messages in the thread,
not counting bare opt-out replies (STOP/YES/...), i.e. the same number the
app computes in ThreadLabelDao.threadSamples.

Split policy: every TEMPLATE is assigned to exactly one split (index % 4:
0,1 -> train, 2 -> dev, 3 -> test), so no template wording is shared between
splits. Instances of one template differ only by slot fills / noise, so the
effective test sample size is the number of test templates, not rows.

Run:  python3 gen_corpus.py <out_dir>
"""
import random
import sys
import os

SEED = 20261013
rng = random.Random(SEED)

FIRST = ["Alex", "Sam", "Jordan", "Priya", "Marcus", "Dana", "Luis", "Mei", "Tariq", "Hannah", "Omar", "Chloe",
         "Devon", "Rosa", "Ken", "Ines", "Noah", "Yuki", "Ava", "Ben", "Zoe", "Ravi", "Tess", "Gabe"]
LAST = ["Nguyen", "Okafor", "Silva", "Brandt", "Haddad", "Kowalski", "Reyes", "Tanaka", "Moreau", "Patel"]
BIZ = ["Acme", "Globex", "Initech", "Contoso", "Umbra", "Hooli", "Vandelay", "Soylent", "Stark Mart", "Wonka"]
BANK = ["Acme Bank", "Globex Credit Union", "Initech Federal", "Contoso Savings", "Hooli Pay"]
SHIP = ["ParcelPal", "ShipFast", "QuickDrop", "Vandelay Freight", "BoxBee"]
CITY = ["Austin", "Denver", "Seattle", "Boston", "Phoenix", "Portland", "Miami", "Chicago"]
SVC = ["Google", "Instagram", "Uber", "WhatsApp", "Acme", "Globex", "Contoso", "Hooli", "Stripe", "Discord"]
ITEM = ["sneakers", "headphones", "jacket", "blender", "backpack", "desk lamp", "yoga mat", "coffee maker"]
SLUG = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
TLD_BAD = ["top", "xyz", "click", "icu", "vip", "cc", "live", "shop", "buzz", "help", "cfd", "sbs", "info"]
SHORT = ["bit.ly", "tinyurl.com", "cutt.ly", "rb.gy", "is.gd", "t.ly", "shorturl.at", "ow.ly"]
AREA = ["415", "212", "512", "646", "303", "206", "617", "602", "312", "305"]
TOLLFREE = ["800", "888", "877", "866", "855", "844", "833"]
ALPHA = ["ACMEBANK", "PARCELPAL", "GLOBEXCU", "CONTOSO", "INITECH", "SHIPFAST", "HOOLIPAY", "UMBRA-RX", "BOXBEE", "WONKA"]


def slug(n=6):
    return "".join(rng.choice(SLUG) for _ in range(n))


def digits(n):
    return "".join(rng.choice("0123456789") for _ in range(n))


def phone_addr():
    return "+1%s555%s" % (rng.choice(AREA), "01%02d" % rng.randrange(100))


def tollfree_addr():
    return "+1%s555%s" % (rng.choice(TOLLFREE), "01%02d" % rng.randrange(100))


def short_addr():
    return digits(rng.choice([5, 5, 6, 6, 6]))


def alpha_addr():
    return rng.choice(ALPHA)


def email_addr():
    return "%s@example.com" % (digits(10) if rng.random() < .5 else rng.choice(FIRST).lower() + digits(3))


def intl_addr():
    return "+44" + "7700900" + digits(3)


SENDERS = {"short": short_addr, "alpha": alpha_addr, "phone": phone_addr, "toll": tollfree_addr,
           "email": email_addr, "intl": intl_addr}


def slots():
    f = rng.sample(FIRST, 3)
    b = rng.choice(BIZ)
    bad_dom = "%s-%s.example-%s.%s" % (b.lower().split()[0], rng.choice(["verify", "secure", "login", "support", "pay", "help"]),
                                       rng.choice(["portal", "svc", "web", "id"]), rng.choice(TLD_BAD))
    return {
        "first": f[0], "first2": f[1], "first3": f[2], "last": rng.choice(LAST),
        "biz": b, "bank": rng.choice(BANK), "ship": rng.choice(SHIP), "city": rng.choice(CITY),
        "svc": rng.choice(SVC), "item": rng.choice(ITEM),
        "amt": "$%d.%02d" % (rng.randrange(3, 900), rng.randrange(100)),
        "amt0": "$%d" % rng.choice([2, 5, 8, 12, 35, 60, 150, 480, 1000, 2500]),
        "last4": digits(4), "code6": digits(6), "code4": digits(4), "code8": digits(8), "code5": digits(5),
        "code6d": "%s-%s" % (digits(3), digits(3)),
        "time": "%d:%02d %s" % (rng.randrange(1, 13), rng.choice([0, 15, 30, 45]), rng.choice(["AM", "PM"])),
        "hour": "%d%s" % (rng.randrange(1, 12), rng.choice(["am", "pm"])),
        "day": rng.choice(["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]),
        "date": "%s %d" % (rng.choice(["Oct", "Nov", "Dec", "Jan"]), rng.randrange(1, 29)),
        "n": rng.choice([5, 10, 15, 20, 25, 30]), "pct": rng.choice([10, 15, 20, 25, 30, 40, 50, 60]),
        "mins": rng.choice([3, 5, 10, 15]), "ph": "555-01%02d" % rng.randrange(100),
        "tf": "1-%s-555-01%02d" % (rng.choice(TOLLFREE), rng.randrange(100)),
        "order": "#%s" % digits(5), "track": "%s%s" % (rng.choice(["1Z", "TBA", "94"]), digits(10)),
        "url": "https://%s.example.com/%s" % (b.lower().split()[0], slug()),
        "short": "%s/%s" % (rng.choice(SHORT), slug(7)),
        "bad": "https://%s/%s" % (bad_dom, slug(5)),
        "bad_http": "http://%s/%s" % (bad_dom, slug(5)),
        "ipurl": "http://%d.%d.%d.%d/%s" % (rng.randrange(11, 223), rng.randrange(256), rng.randrange(256), rng.randrange(1, 255), slug(4)),
        "wa": "+1 555 01%02d" % rng.randrange(100),
        "ticket": "TCK-%s" % digits(4), "pr": "#%d" % rng.randrange(100, 9999),
        "svc_host": rng.choice(["api-gateway", "payments", "checkout", "auth", "search", "billing"]),
    }


NOISE_EMOJI = ["", "", "", " 😂", " 🙏", " 👍", " ❤️", " 😊", " 🔥"]


def noisy(t, strength):
    """Light casual-text noise for human senders."""
    if strength == 0:
        return t
    r = rng.random()
    if r < .15:
        t = t.lower()
    elif r < .22:
        t = t.rstrip(".!?")
    elif r < .27:
        t = t.replace(" you", " u").replace(" are ", " r ").replace("your", "ur")
    t += rng.choice(NOISE_EMOJI) if rng.random() < .35 else ""
    return t


# ---------------------------------------------------------------------------
# Template library.  Each entry: (category, label, sender_kinds, replies_policy, noise, per_template_n, [templates])
# A template is a string or a list of strings (newest first, multi-message thread).
# replies_policy: 'none' | 'conv' (usually replied) | 'maybe' (sometimes 1) | 'newcontact'
# ---------------------------------------------------------------------------
LIB = []


def add(category, label, senders, replies, noise, n, templates):
    LIB.append((category, label, senders, replies, noise, n, templates))


# ============================ OTP_2FA ======================================
add("otp", "OTP_2FA", ["short", "alpha", "toll", "phone"], "none", 0, 14, [
    "Your {svc} verification code is {code6}. Do not share this code with anyone.",
    "{code6} is your {svc} code. Don't share it.",
    "Your one-time passcode is {code6}. It expires in {mins} minutes.",
    "Use {code6} to log in to your {biz} account. Never share this code.",
    "{svc}: {code6} is your security code. If you didn't request it, ignore this message.",
    "<#> {code6} is your {svc} verification code. {slug_hash}",
    "G-{code6} is your Google verification code.",
    "Your OTP is {code4}. Valid for {mins} minutes. Do not disclose it to anyone.",
    "{biz} login code: {code6d}. Expires in {mins} min.",
    "Your {svc} login code is {code8}. We'll never call you to ask for it.",
    "Enter {code6} to confirm your sign-in to {biz}. This code is valid for {mins} minutes.",
    "Verification code: {code6}",
    "To verify your phone number, enter code {code5}. Msg&data rates may apply.",
    "Your {bank} security code is {code6}. {bank} will never ask you for this code. Do not share.",
    "{code6} is your {svc} confirmation code",
    "Use code {code6} to reset your {svc} password. This code expires in {mins} minutes.",
    "Your 2FA code: {code6}. Reply HELP for help.",
    "Do not share: {code6} is the one time password (OTP) for your transaction of {amt} at {biz}. Valid {mins} mins.",
    "{svc} PIN {code4}. Use it to finish signing in.",
    "[{svc}] Your temporary passcode: {code6}. Never forward this message.",
    ["Your {svc} code is {code6}", "Your {svc} code is {code6}", "{code6} is your {svc} verification code"],
])

# ============================ SCAM =========================================
add("scam_toll_parcel", "SCAM", ["phone", "email", "intl", "phone"], "none", 0, 12, [
    "[{ship}] Your package is on hold due to an incomplete address. Update within 12 hours to avoid return: {bad}",
    "USPS: Your parcel could not be delivered. A redelivery fee of $1.99 is required. Pay here: {bad}",
    "FINAL NOTICE: You have an unpaid toll balance of {amt}. Pay now to avoid a late fee of $50: {bad}",
    "E-ZPass: outstanding toll invoice {amt}. Failure to pay by {date} will result in fines and license suspension. {bad}",
    "{ship}: customs fee of {amt} is due before we can deliver your package. Pay at {bad_http}",
    "Your package {track} is pending. Confirm your address and pay the small handling fee: {short}",
    "Delivery attempt failed. Reschedule at {bad} or your item will be returned to sender.",
    "Unpaid parking violation. Pay {amt} immediately to avoid additional penalties: {bad}",
])

add("scam_account", "SCAM", ["phone", "email", "intl", "alpha", "phone"], "none", 0, 12, [
    "{bank} ALERT: Your account has been locked due to suspicious activity. Verify your identity now: {bad}",
    "Your {bank} card was suspended. Confirm your details within 24 hours or your account will be closed: {bad}",
    "Security notice: unusual sign-in on your {svc} account. Secure your account immediately at {short}",
    "{svc}: your payment failed and your subscription will be cancelled. Update billing info: {bad}",
    "Apple ID locked. Your account has been disabled for security reasons. Restore access: {bad}",
    "Fraud department: a charge of {amt} to {biz} was approved. If this was not you call {ph} now to cancel.",
    "We noticed a problem with your {bank} online banking. Login to {ipurl} to avoid permanent suspension.",
    "Your {svc} password expires today. Keep your password: {short}",
    "Action required: your {biz} account will be terminated. Verify now {bad}",
])

add("scam_gov", "SCAM", ["phone", "intl", "email"], "none", 0, 12, [
    "IRS: You have an unpaid tax balance. Legal action will be taken. Call {ph} immediately to avoid arrest.",
    "DMV Notice: outstanding traffic ticket of {amt}. Pay by {date} to avoid license suspension: {bad}",
    "Social Security Administration: your number has been suspended due to suspicious activity. Call {ph} now.",
    "Your tax refund of {amt} is ready. Submit your bank details to claim: {bad}",
    "Final warning: a warrant has been issued under your name. Contact the officer at {ph} immediately.",
    "Medicare: you qualify for a new benefits card. Confirm your SSN here {short}",
])

add("scam_prize", "SCAM", ["phone", "short", "intl", "email"], "none", 0, 12, [
    "Congratulations {first}! You have been selected to receive a $1000 gift card. Claim it now: {short}",
    "You've won! Your number was picked in our weekly draw. Claim your prize within 24 hours: {bad}",
    "Dear customer, you are the lucky winner of an iPhone 15. Click to claim {bad}",
    "{biz} loyalty: you have been chosen for a free $500 reward card. Act now {short}",
    "Amazon gift card of $750 is waiting for you. Complete the short survey to claim: {bad}",
    "WINNER!! As a valued network customer you have been selected to receive a prize reward of 900 pounds. To claim call {ph}. Claim code KL341. Valid 12 hours only.",
])

add("scam_invest_job", "SCAM", ["phone", "intl", "phone", "email"], "none", 1, 12, [
    "Hello {first}, I made $12,000 last week trading USDT with my mentor. She is accepting students, message her on WhatsApp {wa}",
    "We are hiring remote assistants. Earn $200-$500 per day, no experience needed. Contact HR on WhatsApp {wa}",
    "Part-time job offer: like and review products, earn {amt0} commissions daily. Add us on Telegram {short}",
    "Your resume was selected. Position: online customer rater, $300 per day. Reply YES or add {wa} on WhatsApp.",
    "Guaranteed returns of 30% weekly with our crypto platform. Deposit {amt0} to start, withdraw anytime: {bad}",
    "Hi {first}, our AI trading bot made members 8x last month. Join the private group: {short}",
    "I'm from {city}, a trader. My uncle taught me forex and I earn passive income every day. Want me to show you? Add me on WhatsApp {wa}",
])

add("scam_wrongnum", "SCAM", ["phone", "intl", "phone"], "none", 1, 12, [
    "Sorry, wrong number! But you seem very nice 😊 I'm {first2} from {city}. I invest in crypto, would you like to learn?",
    "Hi {first}, long time no see! I'm in {city} for business, are we still on for dinner? Also I have an investment tip for you",
    "Hello, is this {first}? We met at the conference last month. Add me on WhatsApp {wa}, I'd like to share a great opportunity.",
    "Hey, sorry I think I texted the wrong person 🙈 You sound sweet though. I'm a nurse in {city}. Can we be friends? Telegram {short}",
    "Mom/Dad it's me, I broke my phone and this is my new number. Please don't call. I need {amt0} urgently, can you send it by gift card?",
    "Is this you in this video? {short}",
    "omg is that you in this video?? {bad}",
    "{first} look at this photo of you!! {short}",
])

add("scam_callback", "SCAM", ["phone", "email", "toll", "intl"], "none", 0, 10, [
    "Amazon: an order of {amt} for an iPhone was placed on your account. If this wasn't you call {ph} to cancel.",
    "PayPal: you sent {amt} to {first2} {last}. If you did not authorize this, call {ph} immediately.",
    "Norton: your subscription renewal of {amt} will be charged today. To cancel call {ph}.",
    "Geek Squad invoice {order}: {amt} charged. Call {ph} within 24 hours for a refund.",
    "Your Coinbase wallet withdrawal of 0.5 BTC is pending. If you did not request this contact support at {ph} now.",
])

# ============================ SPAM =========================================
add("spam_unsolicited", "SPAM", ["phone", "intl", "toll", "email", "short"], "none", 0, 12, [
    "Hi {first}, are you still interested in selling your home? We pay cash and close in 7 days. Reply YES for an offer.",
    "Final notice about your vehicle's extended warranty. Press 1 to speak to an agent before your coverage lapses.",
    "You are pre-approved for a $5000 personal loan! No credit check. Apply now: {short}",
    "Get 200 FREE SPINS at {biz} Casino, plus a 300% deposit bonus. Play now {short}",
    "Hey handsome, want to chat? I'm lonely tonight 😘 Click {short}",
    "Boost your website traffic with our SEO services, guaranteed first page ranking. Reply for a free quote.",
    "Hi, this is {first2} with {biz} Solar. Homeowners in {city} can cut electric bills by 80%. Are you the homeowner?",
    "Lose 20 pounds in 30 days with this one weird trick. Order today: {short}",
    "Dear customer, your loan of $10,000 is approved. Contact our agent at {ph}.",
    "URGENT: $BIGCOIN is about to 100x. Get in before Monday, join our group {short}",
    "Make money from home!!! Click here {short} limited spots",
    "Free entry in 2 a weekly comp to win FA Cup final tkts 21st May. Text FA to 87121 to receive entry question(std txt rate)T&C's apply",
    "Congrats! You qualify for a FREE cruise vacation for two. Call {ph} to book.",
    "Cheap meds online, no prescription needed. Visit {short}",
    "Hello {first}, we are looking to buy cars in {city}. Text us the year and mileage for an instant offer.",
])

# ============================ PROMO ========================================
add("promo_retail", "PROMO", ["short", "short", "phone", "toll", "alpha"], "maybe_stop", 0, 12, [
    "{biz}: {pct}% off everything this weekend only! Shop now {url}. Reply STOP to opt out.",
    "FLASH SALE 🔥 Take {pct}% off sitewide today. Use code SAVE{pct} at checkout. Msg&data rates may apply. Text STOP to cancel.",
    "{biz} Rewards: you have 500 bonus points expiring {date}. Redeem them on your next purchase! {url} Reply STOP to unsubscribe",
    "New arrivals just dropped! Free shipping on orders over $50. Shop {short} STOP to end",
    "Hi {first}, it's {first2} at {biz} Salon! We have openings this week and {pct}% off your first visit. Book at {url}. Reply STOP to opt out.",
    "{biz}: Buy one get one free on all {item}. Today only. Show this text in store. Reply STOP to unsubscribe.",
    "Exclusive offer for VIP members: extra {pct}% off clearance. Ends tonight. {url} Unsubscribe: reply STOP",
    "Your {biz} coupon: $10 off a purchase of $40 or more. Valid through {date}. Terms apply. Reply STOP to stop.",
    "Don't miss our Black Friday preview! Early access starts now: {url} Txt STOP 2 end",
    "Craving pizza? Order today and get a free side. Tap {short}. Reply STOP to opt-out.",
    "{biz}: we miss you! Come back and enjoy {pct}% off your next order. Limited time. {url}",
])

add("promo_civic", "PROMO", ["short", "phone", "short"], "maybe_stop", 0, 10, [
    "Hi {first}, it's {first2} {last} running for City Council. Chip in $5 before tonight's deadline: {url} Paid for by Friends of {last}. Reply STOP to quit.",
    "Early voting starts {day}! Find your polling place and make a plan to vote: {url}. Text STOP to opt out.",
    "Your gift of {amt0} can feed a family this winter. Donate today: {url}. Reply STOP to end.",
    "{biz} Foundation: we're 80% to our goal! Can you chip in {amt0} today? {short} Text STOP to unsubscribe.",
    "Join our volunteer phone bank this {day}! RSVP here {url}. Msg&data rates may apply. STOP to opt out.",
    "Poll: do you support the new transit bill? Reply 1 for yes, 2 for no. Paid for by Citizens for Better Transit. STOP to end.",
])

add("promo_service", "PROMO", ["short", "toll", "alpha"], "maybe_stop", 0, 8, [
    "{biz} Mobile: upgrade to the new plan and get a free phone. Visit {url} Reply STOP to opt out.",
    "Grand opening this Saturday! Join us at {biz} for free samples and giveaways. Reply STOP to unsubscribe.",
    "Take our 2-minute survey and enter to win a $100 gift card. {url} Reply STOP to opt out.",
    "{biz} Insurance: bundle home and auto and save up to {pct}%. Get a quote: {url}. Text STOP to end.",
    "Your free trial ends soon. Upgrade to Premium for {amt0}/mo: {url}. Reply STOP to cancel texts.",
])

# ============================ WORK =========================================
add("work_human", "WORK", ["phone"], "conv", 1, 14, [
    "Standup moved to {time} today, same zoom link as the calendar invite.",
    "Can anyone cover my {hour} shift on {day}? I'll take your next closing shift in return.",
    "Heads up, I'm out on PTO {day} through {day}. Ping {first2} for anything urgent.",
    "Client wants the revised deck by EOD. Can you send me the latest version?",
    "Are you free for a quick sync before the {hour} meeting? Want to align on the roadmap.",
    "You're on call this weekend, right? I'll be offline {day}. Can you take my pager?",
    "Prod deploy is done. Watch the dashboards for errors, roll back if you see anything weird.",
    "PR {pr} is ready for review when you have a sec. It touches the billing service.",
    "Hi {first}, this is {first2} from {biz} Talent. Your profile stood out for our Staff Engineer opening. Open to a quick call this week?",
    "Reminder: sprint planning at {time} in conf room B. Bring your estimates.",
    "Mr. {last} confirmed the walkthrough for {day} at {time}. Contract is in your email, please review before then.",
    "Your timesheet is due today by 5. Please submit before EOD or payroll will miss you.",
    "Can you join the incident bridge? Checkout is returning 500s and we need another set of eyes.",
    "Interview loop for the PM candidate is {day} at {time}. You're the 2nd round. Zoom link in the invite.",
    "Quarterly review slides are in the shared drive. Can you add your team's numbers by {day}?",
    "The customer escalated ticket {ticket}. Can you take a look before the {hour} call?",
    "I'll be working from home tomorrow, dial in for the all-hands if you need me",
    "Got the signed SOW from the client. Kicking off the project Monday. Can you book the team for the kickoff?",
    ["Sounds good, I'll update the Jira board", "did you see the staging build fail again?", "morning, quick question about the release"],
])

add("work_automated", "WORK", ["short", "alpha", "toll"], "none", 0, 10, [
    "[PagerDuty] TRIGGERED #{code4}: {svc_host} high error rate (SEV2). Ack: {url}",
    "ALERT: {svc_host} latency p99 > 2s for 5m. On-call: {first}. Runbook: {url}",
    "Incident {ticket} resolved. Service {svc_host} is healthy again. Postmortem on {day}.",
    "{biz} IT: your VPN certificate expires in 3 days. Renew it via the Company Portal.",
    "Shift reminder: you are scheduled {time} to 5:00 PM {day} at Store {code4}. Reply C to confirm.",
    "Opsgenie: critical alert for {svc_host} acknowledged by {first2}. Escalating in 10 minutes.",
    "Build failed on main: {pr} 'Fix checkout rounding'. See logs: {url}",
])

# ============================ PERSONAL (people) ============================
add("personal_plans", "PERSONAL", ["phone"], "conv", 1, 14, [
    "running like 10 min late, grab us a table?",
    "are you free saturday? thinking of hiking if the weather holds",
    "dinner at 7 works. I'll make the reservation",
    "lol that is so funny 😂😂",
    "ok",
    "k see you there",
    "Thanks for dropping off the keys, you're a lifesaver!",
    "can you pick up milk and eggs on the way home?",
    "Happy birthday {first}!! Hope you have the best day 🎂",
    "Just landed. Heading to baggage claim now",
    "mom: call me when you get a sec, nothing urgent",
    "who's driving to the game tomorrow?",
    "We're at the park by the fountain, the kids want ice cream after",
    "omg did you see what happened on the finale 😭",
    "Proud of you. Call me tonight if you want to talk about it ❤️",
    "movie at {hour}? I'll buy the tickets",
    "sorry I missed your call, was in the shower. What's up?",
    "Don't forget grandma's birthday is {day}! I'm bringing the cake",
    "yes!!! congrats!!! 🎉",
    "he said yes to the offer, I'm so relieved",
    "I'll be there in {n} min",
    ["haha ok see you then", "should I bring anything?", "come over for dinner {day}?"],
])

add("personal_hard", "PERSONAL", ["phone"], "conv", 1, 12, [
    "send me the wifi code again, I forgot it",
    "my zip code is 78666, right? the form keeps rejecting it",
    "you won the bet lol. you owe me a coffee",
    "check out this article, it's wild: https://news.example.com/a/{code4}",
    "u free tonight? we should celebrate",
    "the bank called me about that charge, it was me, ignore the alert",
    "Venmo'd you {amt0} for the tickets, let me know if it didn't go through",
    "what's your address? I'm sending a package",
    "Call me when you can. {ph}",
    "congrats on the new job! claim your free drink at the bar on me 🍻",
    "did you get the verification email? mine never came",
    "Your sister said to call the pharmacy before they close",
    "Please pay me back for lunch {amt}",
    "I got a text saying my package is delayed lol",
    "free to chat? urgent but good news!!",
    "this is the new number for the contractor, text him about the leak: {ph}",
    "Pin is {code4} for the garage, don't tell anyone 😂",
    "my account got locked again lol, resetting my password now",
    "can you review my resume? the link is https://docs.example.com/d/{code4}",
    "Hi it's {first2}, I got a new phone so this is my new number!",
])

add("personal_newcontact", "PERSONAL", ["phone"], "newcontact", 1, 10, [
    "Hey {first}, this is {first2} from your {day} yoga class. Can I get your email for the photos?",
    "Hi! I'm {first2}, the neighbor from 3B. Your package ended up at my door.",
    "Hi, this is {first2} {last}, the plumber. I can come {day} between 1 and 3. Does that work?",
    "Hey it's {first2}, Sam gave me your number. Are you still selling the couch?",
    "Hi {first}, this is Dr. {last}'s office. Please call us back at {ph} when you can.",
    "Hello, I'm calling about the room for rent. Is it still available?",
    "This is {first2}, your Uber driver. I'm outside in a gray Honda.",
    "Wrong number? sorry!",
])

# ============================ PERSONAL (transactional) =====================
add("txn_bank", "PERSONAL", ["short", "alpha", "toll"], "maybe_stop", 0, 12, [
    "{bank}: Purchase of {amt} at {biz} with card ending {last4}. Not you? Call {tf}.",
    "{bank} Fraud Alert: Did you attempt a purchase of {amt} at {biz}? Reply YES or NO.",
    "{bank}: Your account ending {last4} has a new deposit of {amt}.",
    "{bank}: Low balance alert. Your available balance is {amt}. Reply STOP to end alerts.",
    "{bank}: Your payment of {amt} was received. Thank you!",
    "{bank} Alert: A payment of {amt} is due {date}. Log in at {url} to pay.",
    "Zelle: {first2} {last} sent you {amt}. Money has been deposited to your account ending {last4}.",
    "{bank}: Card ending {last4} was used for {amt} at {biz}. If this wasn't you, call {tf} or lock your card in the app.",
    "{bank}: your statement for {date} is ready. View it securely in the app.",
])

add("txn_delivery", "PERSONAL", ["short", "alpha", "toll"], "none", 0, 12, [
    "{ship}: Your package {track} will arrive today between 2-4 PM. Track: {url}",
    "Your {biz} order {order} has shipped! Track it here: {url}",
    "{ship}: Out for delivery. Your driver is {first2} and is 6 stops away.",
    "Your order {order} was delivered to your front door at {time}.",
    "{ship} delivery update: your parcel is delayed by one day due to weather. No action is needed.",
    "{biz}: Your order {order} is ready for pickup at the store. Bring a photo ID.",
    "Your {biz} ride receipt: {amt}. Thanks for riding!",
    "{ship}: We missed you. Your package is at the pickup counter, bring photo ID. Held for 3 days.",
])

add("txn_reminder", "PERSONAL", ["short", "alpha", "toll", "phone"], "maybe_stop", 0, 12, [
    "Reminder: you have an appointment at BrightSmile Dental on {day} at {time}. Reply C to confirm or R to reschedule.",
    "Umbra Pharmacy: Your prescription is ready for pickup. Reply STOP to opt out.",
    "{biz} Airlines: Flight AC{code4} is delayed 45 minutes. New departure {time}, gate B12.",
    "Your {biz} Utilities bill of {amt} is due {date}. Pay at {url}.",
    "Hi {first}, this is a reminder from Dr. {last}'s office about your visit {day} at {time}. Please call {ph} to reschedule.",
    "Your table for 4 at {biz} Grill is confirmed for {day} at {time}. Confirmation number {code5}.",
    "Your car service is complete. Total {amt}. You can pick up the vehicle until 6 PM.",
    "Your {biz} reservation is tomorrow at {time}. Reply 1 to confirm, 2 to cancel.",
    "School closure: {biz} Elementary is closed {day} due to weather.",
])


# ---------------------------------------------------------------------------
def build():
    rows = []
    counters = {}
    for category, label, senders, policy, noise, n, templates in LIB:
        for idx, tpl in enumerate(templates):
            split = ["train", "train", "dev", "test"][idx % 4]
            for k in range(n):
                s = slots()
                s["slug_hash"] = "FA+" + slug(8)
                kind = rng.choice(senders)
                addr = SENDERS[kind]()
                parts = tpl if isinstance(tpl, list) else [tpl]
                bodies = []
                for p in parts:
                    # unused slots in templates are fine; format with fill
                    b = p.format(**s)
                    bodies.append(noisy(b, noise) if noise else b)
                if policy == "none":
                    replies = 0
                elif policy == "conv":
                    replies = rng.choice([0, 1, 2, 3, 5, 8, 12, 20]) if rng.random() < .85 else 0
                    if replies == 0 and rng.random() < .5:
                        replies = 4
                elif policy == "newcontact":
                    replies = rng.choice([0, 0, 1, 2])
                elif policy == "maybe_stop":
                    replies = 0  # STOP/YES replies are excluded from the count
                else:
                    replies = 0
                counters[category] = counters.get(category, 0) + 1
                rows.append((split, "%s-%d-%d" % (category, idx, k), category, label, addr, replies, bodies))
    return rows


def hard_extra(rows):
    """Targeted behaviour cases: a scam-looking text on a thread the user has a conversation with,
    and a scammer the user replied to once ('who is this?')."""
    extra = []
    for k in range(40):
        s = slots()
        # Friend whose account is hijacked: still SCAM even with history, because of strong multi-signal.
        extra.append(("test" if k % 4 == 3 else ("dev" if k % 4 == 2 else "train"), "x-hijack-%d" % k, "hijacked_friend", "SCAM",
                      phone_addr(), rng.choice([6, 9, 14]),
                      ["is this you in this video? %s" % s["short"], "hey, how are you?"]))
        # User replied once to a scammer ('who is this?')
        extra.append(("test" if k % 4 == 3 else ("dev" if k % 4 == 2 else "train"), "x-repliedscam-%d" % k, "replied_to_scammer", "SCAM",
                      phone_addr(), 1,
                      ["Final notice: unpaid toll balance of %s. Pay now to avoid a late fee: %s" % (s["amt"], s["bad"])]))
        # Legit casual friend sending a link and urgency: must stay PERSONAL
        extra.append(("test" if k % 4 == 3 else ("dev" if k % 4 == 2 else "train"), "x-friendlink-%d" % k, "friend_with_link", "PERSONAL",
                      phone_addr(), rng.choice([5, 10, 30]),
                      ["call me asap!! urgent!! see this %s" % s["short"], "you around?"]))
    return extra


def main(out):
    os.makedirs(out, exist_ok=True)
    rows = build() + hard_extra([])
    rng.shuffle(rows)
    files = {sp: open(os.path.join(out, sp + ".tsv"), "w", encoding="utf-8") for sp in ("train", "dev", "test")}
    for f in files.values():
        f.write("id\tcategory\tlabel\taddress\treplies\tbodies\n")
    seen = set()
    for split, rid, category, label, addr, replies, bodies in rows:
        key = (addr, " ||| ".join(bodies))
        if key in seen:
            continue
        seen.add(key)
        clean = [b.replace("\t", " ").replace("\n", " ").replace("\r", " ") for b in bodies]
        files[split].write("\t".join([rid, category, label, addr, str(replies), " ||| ".join(clean)]) + "\n")
    for f in files.values():
        f.close()


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
