import json
import os
import urllib.request
import urllib.error
import time
import sys

# دعم ترميز UTF-8 على ويندوز لمنع أخطاء charmap cp1252
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

# ==============================================================================
# OSRS AI Dialogue & Security Question Solver Module (Dual-Agent Compliant)
# المعمارية: Cache-First Architecture + External AI Fallback
# 1. فحص الكاش المحلي فائق السرعة (0ms - بدون استهلاك كوتا أو نت)
# 2. في حال كان السؤال جديد أو غير موجود في الكاش -> استدعاء Groq AI (qwen/qwen3.8-27b)
# 3. تدوير تلقائي للمفاتيح + هيدر متوافق مع Cloudflare (User-Agent: Mozilla/5.0)
# ==============================================================================

GROQ_ACCOUNTS_CANDIDATES = [
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".AAA_GGG_iii_VIBE_CODING_مجلد_المزودين", "بعدين", "groq", "accounts_Groq.json"),
    r"d:\SMS\.hRhR_gateway-service\.AAA_GGG_iii_VIBE_CODING_مجلد_المزودين\بعدين\groq\accounts_Groq.json",
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "accounts_Groq.json")
]
GROQ_ACCOUNTS_FILE = next((p for p in GROQ_ACCOUNTS_CANDIDATES if os.path.exists(p)), GROQ_ACCOUNTS_CANDIDATES[0])

# ------------------------------------------------------------------------------
# 1️⃣ الكاش المحلي الثابت (38+ سؤال وإجابة رسمية من لعبة OSRS)
# ------------------------------------------------------------------------------
LOCAL_SECURITY_CACHE = {
    "randomly selected for a prize": "No way! I'm reporting you to Jagex!",
    "logged in while i'm out of the room": "No.",
    "remove a hijacker": "Use the Account Recovery System.",
    "make you a player moderator": "Don't give them the information and send an 'Abuse report'.",
    "watching a stream": "Report the stream as a scam. Real Jagex streams have a 'verified' mark.",
    "do a difficult quest": "Don't give them my password.",
    "who can i give my password": "Nobody.",
    "how do i set up two-factor": "Through account settings on oldschool.runescape.com.",
    "example of a good bank pin": "The birthday of a famous person or event.",
    "check your stats": "Don't give out your password to anyone. Not even close friends.",
    "comment on the forum post": "Report the player for phishing.",
    "invitation via in- game chat or email": "Report the incident and do not click any links.",
    "how do i set a bank pin": "Talk to any banker.",
    "moderator asks me for my account details": "Politely tell them no, then use the 'Report Abuse' button.",
    "after you answer the question": "Read the text and follow the advice given.",
    "prevent me from saying my pin": "No.",
    "that sound?": "No way! You'll just take my gold for your own! Reported!",
    "verify my identity": "Delete it - it's a fake!",
    "website claims": "Nothing, it's a fake.",
    "react?": "Don't share your information and report the player.",
    "make you a member for free": "Don't tell them anything and click the 'Report Abuse' button.",
    "okay to buy an old school": "No, you should never buy an account.",
    "invitation via social media": "Report the incident and do not click any links.",
    "where is it safe": "Only on the Old School RuneScape website.",
    "best way to secure your account": "Two-factor authentication on your account and your registered email.",
    "share my account with": "Nobody.",
    "purchase items and/or gold": "Do not visit the website and report the player who messaged you.",
    "whose responsibility": "Me.",
    "someone to level your account": "No, you should never allow anyone to use your account.",
    "account is compromised": "Secure my device and reset my password.",
    "respond?": "Decline the offer and report that player.",
    "password backwards": "Don't type in my password backwards and report the player.",
    "keylogger or virus": "Virus scan my device then change my password.",
    "registered email secure": "Set up two-factor authentication with my email provider.",
    "characteristic of a secure password": "It's never reused on other websites or accounts.",
    "lose out! what do you say?": "Nope, you're tricking me into going somewhere dangerous.",
    "not through responding to emails.": "No.",
    "authenticator": "Through account settings",
    "phishing": "Report the player",
    "giveaway": "Report the incident and do not click any links."
}

# كلمات وأنماط الأمان الاحتياطية
FALLBACK_SAFE_PATTERNS = [
    "reporting you to jagex",
    "report the incident and do not click",
    "report the stream as a scam",
    "report the player",
    "report abuse",
    "use the account recovery",
    "don't give them",
    "don't give out",
    "nobody",
    "through account settings",
    "birthday of a famous",
    "talk to any banker",
    "follow the advice",
    "just take my gold",
    "it's a fake",
    "nothing, it's a fake",
    "delete it",
    "don't share",
    "never buy an account",
    "only on the old school runescape website",
    "two-factor",
    "do not visit",
    "never allow anyone",
    "secure my device",
    "decline the offer",
    "don't type in my password backwards",
    "virus scan",
    "never reused",
    "tricking me into going somewhere dangerous",
    "not through responding to emails",
    "fancy boots",
    "fighting boots",
    "fancy",
    "yes",
    "no."
]

class LocalCacheSolver:
    """المحلل المحلي فائق السرعة - 0ms بدون استهلاك أي موارد"""
    @staticmethod
    def solve(question: str, options: list[str]) -> str | None:
        if not options:
            return None

        # 1. خيار الجزمة وموافقات التأكيد المباشرة
        for opt in options:
            opt_l = opt.lower()
            if "fancy" in opt_l:
                return opt
            if opt_l in ["yes", "yes.", "i'd like fancy boots!"]:
                return opt

        # 2. البحث في القاموس الثابت بالسؤال
        q_clean = question.lower()
        for k_q, ans_text in LOCAL_SECURITY_CACHE.items():
            if k_q in q_clean or q_clean in k_q:
                # محاولة مطابقة الإجابة المخزنة مع الخيارات المعروضة
                ans_l = ans_text.lower()
                for opt in options:
                    opt_l = opt.lower()
                    if opt_l in ans_l or ans_l in opt_l:
                        return opt
                # في حال كانت الإجابة جزئية
                for opt in options:
                    words = [w for w in ans_l.split() if len(w) > 3]
                    if any(w in opt.lower() for w in words):
                        return opt

        # 3. مطابقة الخيارات مع أنماط الأمان المعتمدة
        for opt in options:
            opt_clean = opt.lower()
            for pattern in FALLBACK_SAFE_PATTERNS:
                if pattern in opt_clean:
                    return opt

        return None

class GroqAISolver:
    """مزود الذكاء الاصطناعي السحابي المعزول عبر Groq Cloud API"""
    def __init__(self):
        self.keys = []
        self.model = "qwen/qwen3.8-27b"
        self.current_key_idx = 0
        self._load_keys()

    def _load_keys(self):
        if os.path.exists(GROQ_ACCOUNTS_FILE):
            try:
                with open(GROQ_ACCOUNTS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    self.model = data.get("models", {}).get("vision", self.model)
                    for acc in data.get("accounts", []):
                        if acc.get("is_active", True) and acc.get("api_key"):
                            self.keys.append(acc["api_key"])
            except Exception:
                pass

    def solve(self, question: str, options: list[str]) -> str | None:
        if not options or not self.keys:
            return None
        if len(options) == 1:
            return options[0]

        options_str = "\n".join([f"- {opt}" for opt in options])
        system_prompt = (
            "You are an Old School RuneScape security expert. "
            "You are answering questions at the Stronghold of Security doors to safely pass through. "
            "Pick the single safest option that follows Jagex account security rules (never share details, report phishers, use 2FA, ignore giveaways). "
            "Return ONLY the exact option text from the provided choices without quotes or extra text."
        )
        user_prompt = f"Question: {question}\nOptions:\n{options_str}"

        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "temperature": 0.0,
            "max_tokens": 50
        }
        data_bytes = json.dumps(payload).encode("utf-8")

        # تجربة المفاتيح بالتدوير مع تجاوز أخطاء 429 و 403
        attempts = min(3, len(self.keys))
        for _ in range(attempts):
            key = self.keys[self.current_key_idx]
            self.current_key_idx = (self.current_key_idx + 1) % len(self.keys)

            req = urllib.request.Request(
                "https://api.groq.com/openai/v1/chat/completions",
                headers={
                    "Authorization": f"Bearer {key}",
                    "Content-Type": "application/json",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                },
                data=data_bytes
            )

            try:
                with urllib.request.urlopen(req, timeout=3.5) as resp:
                    res = json.loads(resp.read().decode("utf-8"))
                    ans = res["choices"][0]["message"]["content"].strip()
                    # مطابقة رد الذكاء الاصطناعي مع الخيارات
                    ans_l = ans.lower()
                    for opt in options:
                        opt_l = opt.lower()
                        if opt_l in ans_l or ans_l in opt_l:
                            return opt
            except urllib.error.HTTPError as he:
                # في حال Rate-Limit أو خطأ بالمفتاح، نجرب المفتاح التالي
                continue
            except Exception:
                continue

        return None

# تهيئة الكائنات الموحدة
_local_solver = LocalCacheSolver()
_groq_solver = GroqAISolver()

# ------------------------------------------------------------------------------
# 2️⃣ المدخل الموحد المعزول لحل حوارات الأمان (Single Source of Truth)
# ------------------------------------------------------------------------------
def solve_security_dialogue(question: str, options: list[str]) -> str | None:
    """
    الدالة المركزية لحل الأسئلة (Cache-First Architecture):
    1. المستوى الأول: الكاش المحلي (0ms - سرعة فائقة ومجاني 100%)
    2. المستوى الثاني: الذكاء الاصطناعي Groq (Fallback ذكي وسريع في <1s)
    3. المستوى الثالث: الخيار الافتراضي الآمن
    """
    if not options:
        return None

    # 1️⃣ الكاش المحلي أولاً (Cache-First)
    local_ans = _local_solver.solve(question, options)
    if local_ans:
        print(f"⚡ [Local Cache (0ms)]: وقع الاختيار على -> '{local_ans}'")
        return local_ans

    # 2️⃣ استدعاء المساعد الخارجي الذكي (Groq AI Fallback)
    print("🤖 [AI Fallback]: السؤال غير موجود بالكاش المحلي، جاري الاستعانة بـ Groq AI...")
    ai_ans = _groq_solver.solve(question, options)
    if ai_ans:
        print(f"🤖 [Groq AI Resolved]: وقع الاختيار على -> '{ai_ans}'")
        return ai_ans

    # 3️⃣ الخيار الافتراضي
    fallback = options[0]
    print(f"🛡️ [Safe Default]: تم اختيار أول خيار كاحتياط -> '{fallback}'")
    return fallback

# اسم بديل متوافق تماماً (Backward Compatibility Alias)
select_best_answer = solve_security_dialogue

if __name__ == "__main__":
    print("--- اختبار وحدة الذكاء الاصطناعي المعزولة (Cache-First) ---")
    # اختبار الكاش المحلي
    q1 = "Who can I give my password to?"
    opts1 = ["Nobody.", "My best friend.", "A Jagex Moderator."]
    print("Test 1 (Cache):", solve_security_dialogue(q1, opts1))

    # اختبار الـ Fallback عبر Groq
    q2 = "What should you do if an unknown player offers free doubling gold in Lumbridge?"
    opts2 = ["Give them half your gold.", "Ignore them and report for scamming.", "Ask for proof."]
    print("Test 2 (Groq AI):", solve_security_dialogue(q2, opts2))
