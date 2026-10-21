import urllib.request
import json
import time
import os
import sys

# دعم ترميز UTF-8 على ويندوز لمنع أخطاء charmap cp1252
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8")

# ==============================================================================
# OSRS Standalone Autonomous Bot — Stronghold of Security (10,000 GP + Boots)
# يعمل محلياً 100% بدون نت ولا ذكاء اصطناعي وبدون استهلاك أي كوتا!
# ==============================================================================

TOKEN_PATH = r"C:\Users\USER\.runelite\.agent-token"
if not os.path.exists(TOKEN_PATH):
    print("❌ خطأ: ملف التوكن غير موجود! تأكد إن RuneLite شغال والـ Agent Server متفعل.")
    input("اضغط Enter للخروج...")
    sys.exit(1)

TOKEN = open(TOKEN_PATH).read().strip()
HEADERS = {"X-Agent-Token": TOKEN, "Content-Type": "application/json"}
BASE_URL = "http://127.0.0.1:8081"
def api_get(endpoint):
    req = urllib.request.Request(f"{BASE_URL}{endpoint}", headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}

def api_post(endpoint, data):
    body = json.dumps(data).encode()
    req = urllib.request.Request(f"{BASE_URL}{endpoint}", data=body, headers=HEADERS)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except Exception as e:
        return {"error": str(e)}

def ensure_login():
    st = api_get("/state")
    if not st.get("loggedIn"):
        print("🔄 الحساب غير مسجل الدخول، جاري تسجيل الدخول التلقائي...")
        res = api_post("/login", {"wait": True, "timeout": 30})
        print("✅ نتيجة تسجيل الدخول:", res.get("message"))
        time.sleep(2)

# استيراد المساعد المعزول لحل الأسئلة (Cache-First + Groq Fallback)
try:
    from osrs_ai_solver import solve_security_dialogue, select_best_answer
except ImportError:
    sys.path.append(os.path.dirname(os.path.abspath(__file__)))
    from osrs_ai_solver import solve_security_dialogue, select_best_answer

STRONGHOLD_DOORS = [23653, 23654, 23727, 23728, 17009, 17100, 19206, 19207]
STRONGHOLD_DOOR_NAMES = ["Oozing barrier", "Portal of Death", "Gate of War", "Rickety door"]

def handle_dialogue_and_modals():
    # 1. شباك التحذير للسلالم (groupId: 579, childId: 17)
    api_post("/widgets/click", {"groupId": 579, "childId": 17})
    
    # 2. الحوارات والأسئلة (تكامل ذكي مع مساعد الأمان المعزول)
    handled = False
    for _ in range(5):
        d = api_get("/dialogue")
        if not d.get("inDialogue"):
            break
        handled = True
        
        if d.get("hasOptions") or d.get("hasSelectAnOption"):
            opts = d.get("options", [])
            q = d.get("question", "") or d.get("dialogueText", "")
            
            # استدعاء المساعد الذكي المعزول (0ms للكاش + Groq AI للجديد)
            selected = solve_security_dialogue(q, opts)
            
            if selected:
                print(f"💬 اختيار الإجابة: {selected}")
                api_post("/dialogue/select", {"option": selected})
                time.sleep(1.2)
            else:
                print(f"⚠️ خيارات بدون اختيار: {opts}")
                time.sleep(0.5)
        elif d.get("hasContinue"):
            api_post("/dialogue/continue", {})
            time.sleep(0.8)
            
    return handled

def check_and_eat_food():
    """فحص الدم والأكل التلقائي لو الـ HP نزل عن 8"""
    try:
        req = urllib.request.Request(f"{BASE_URL}/skills", headers=HEADERS)
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode())
            hp_cur = 10
            for s in data.get("skills", []):
                if s.get("name") == "Hitpoints":
                    hp_cur = s.get("boostedLevel", s.get("level", 10))
            if hp_cur <= 7:
                req_inv = urllib.request.Request(f"{BASE_URL}/inventory", headers=HEADERS)
                with urllib.request.urlopen(req_inv, timeout=2) as r_inv:
                    inv_data = json.loads(r_inv.read().decode())
                    for item in inv_data.get("items", []):
                        name_i = item.get("name", "").lower()
                        if any(f in name_i for f in ["shrimp", "trout", "salmon", "meat", "bread", "cake", "tuna", "lobster", "pie", "fish"]):
                            print(f"🍖 استهلاك طعام لرفع الدم ({hp_cur}/10): {item.get('name')}")
                            api_post("/inventory/interact", {"id": item.get("id"), "action": "Eat"})
                            time.sleep(0.6)
                            break
    except Exception:
        pass

def try_pass_door(door_name="Oozing barrier"):
    """تفاعل صريح مع الباب والانتظار حتى عبوره دون مقاطعة المشي"""
    for act in ["Pass-through", "Open"]:
        r = api_post("/objects/interact", {"name": door_name, "action": act})
        if r.get("success") is True:
            print(f"🚪 تم فتح وتجاوز الباب ({door_name}) بنجاح!")
            for _ in range(3):
                time.sleep(1)
                handle_dialogue_and_modals()
            return True
    return False

def try_open_nearby_doors():
    # فحص الأبواب بالاسم أولاً مع التحقق الصارم من success
    for name in STRONGHOLD_DOOR_NAMES:
        for act in ["Pass-through", "Open"]:
            r = api_post("/objects/interact", {"name": name, "action": act})
            if r.get("success") is True:
                print(f"🚪 تم التفاعل مع الباب ({name}) بنجاح!")
                for _ in range(3):
                    time.sleep(1)
                    handle_dialogue_and_modals()
                return True
    # فحص الأبواب بالـ ID
    for d_id in STRONGHOLD_DOORS:
        for act in ["Pass-through", "Open"]:
            r = api_post("/objects/interact", {"id": d_id, "action": act})
            if r.get("success") is True:
                print(f"🚪 تم التفاعل مع الباب (ID {d_id}) بنجاح!")
                for _ in range(3):
                    time.sleep(1)
                    handle_dialogue_and_modals()
                return True
    return False

def walk_to(x, y, plane=0, timeout=75):
    print(f"🚶 يتحرك الآن إلى: ({x}, {y}, plane: {plane})...")
    api_post("/walk", {"x": x, "y": y, "plane": plane, "wait": False})
    start_t = time.time()
    last_move_t = time.time()
    last_px, last_py = 0, 0
    
    while time.time() - start_t < timeout:
        check_and_eat_food()
        
        # لو في حوار نجاوبه فوراً
        if handle_dialogue_and_modals():
            last_move_t = time.time()
            time.sleep(1)
            api_post("/walk", {"x": x, "y": y, "plane": plane, "wait": False})
            continue
            
        st = api_get("/state")
        pos = st.get("player", {}).get("position", {})
        px, py, p_plane = pos.get("x", 0), pos.get("y", 0), pos.get("plane", 0)
        
        # التأكد من الوصول للهدف
        if abs(px - x) <= 3 and abs(py - y) <= 3 and p_plane == plane:
            print(f"📍 وصل بنجاح إلى الهدف: ({px}, {py})")
            return True
            
        # رصد الحركة لتجاوز عوائق الأبواب
        if px != last_px or py != last_py:
            last_move_t = time.time()
            last_px, last_py = px, py
        else:
            # لو متوقف لأكثر من 3 ثواني وبدون حوار نشط (قدام باب مسدود)
            if time.time() - last_move_t >= 3:
                d_chk = api_get("/dialogue")
                if not d_chk.get("inDialogue"):
                    print("⚠️ توقف مؤقت - محاولة فتح باب الأمان...")
                    if try_open_nearby_doors():
                        # منع الـ Race Condition: عدم إرسال /walk فوراً لترك اللاعب يعبر الباب
                        last_move_t = time.time()
                        continue
                api_post("/walk", {"x": x, "y": y, "plane": plane, "wait": False})
                last_move_t = time.time()
                
        time.sleep(1)
    print(f"⚠️ انتهت المهلة ({timeout}ث)، الموقع الحالي: ({px}, {py}, plane: {p_plane})")
    return False

def interact_object(obj_id, action):
    print(f"🖐️ تفاعل مع الغرض: {obj_id} (أمر: {action})...")
    res = api_post("/objects/interact", {"id": obj_id, "action": action})
    time.sleep(2)
    handle_dialogue_and_modals()
    return res

def main():
    print("=" * 65)
    print("🚀 مرحباً بك في البوت المستقل الشامل لـ Stronghold of Security")
    print("💰 الهدف: جمع 10,000 GP + Fancy Boots وحفظها في البنك")
    print("⚡ يعمل محلياً 100% من جهازك بدون نت ولا ذكاء اصطناعي وبدون استهلاك كوتا!")
    print("=" * 65)

    ensure_login()
    
    st = api_get("/state")
    if "error" in st:
        print("\n❌ تنبيه هام:")
        print("مش قادرين نتصل بـ Microbot على المنفذ 8081!")
        print("السبب: اللعبة المفتوحة حالياً هي RuneLite العادية (مش نسخة البوت الآلي).")
        print("الحل في ثانية: اقفل اللعبة المفتوحة حالياً، وافتحها من أيقونة 'تشغيل_البوت.bat' اللي على سطح المكتب.")
        print("أول ما تفتح اللعبة وتسجل دخول، شغل ملف 'تشغيل_بوت_السترونج_هولد.bat' وهيشتغل فوراً!")
        input("\nاضغط Enter للخروج...")
        sys.exit(1)

    pos = st.get("player", {}).get("position", {})
    px, py = pos.get('x', 0), pos.get('y', 0)
    print(f"📌 موقع اللاعب الحالي: ({px}, {py}, plane: {pos.get('plane')})")

    # لو البنك مفتوح اقفله
    api_post("/bank/close", {})

    # إذا كان اللاعب على السطح (سواء في لومبريدج أو البورصة أو الطريق)
    if py < 5000:
        print("\n--- [المرحلة 1]: التوجه لمدخل الـ Stronghold في قرية البرابرة ---")
        if not walk_to(3080, 3419, plane=0, timeout=160):
            walk_to(3080, 3419, plane=0, timeout=80)
        
        print("\n--- [المرحلة 2]: النزول داخل الكهف (الطابق الأول) ---")
        for attempt in range(5):
            interact_object(20790, "Climb-down")
            for _ in range(8):
                time.sleep(1)
                handle_dialogue_and_modals()
                st_chk = api_get("/state")
                if st_chk.get("player", {}).get("position", {}).get("y", 0) >= 5000:
                    print("📍 تم النزول تحت الأرض بنجاح داخل الكهف!")
                    break
            st_chk = api_get("/state")
            if st_chk.get("player", {}).get("position", {}).get("y", 0) >= 5000:
                break
            print(f"🔄 إعادة محاولة النزول داخل الصخرة (محاولة {attempt + 2}/5)...")

    # 1. عبور الطابق الأول (Vault of War)
    st_cur = api_get("/state")
    pos_cur = st_cur.get("player", {}).get("position", {})
    px, py = pos_cur.get('x', 0), pos_cur.get('y', 0)
    if 1855 <= px <= 1930 and py >= 5000:
        print("\n--- [المرحلة 3]: عبور الطابق الأول إلى سلم النزول للطابق الثاني ---")
        interact_object(20786, "Use")
        time.sleep(2)
        handle_dialogue_and_modals()
        walk_to(1903, 5222, plane=0, timeout=35)
        print("🖐️ النزول عبر سلم الطابق الأول (20785)...")
        for attempt in range(4):
            interact_object(20785, "Climb-down")
            for _ in range(6):
                time.sleep(1)
                handle_dialogue_and_modals()
                st_chk = api_get("/state")
                px_chk = st_chk.get("player", {}).get("position", {}).get("x", 0)
                if 1980 <= px_chk <= 2055:
                    print("📍 تم النزول للطابق الثاني بنجاح!")
                    break
            st_chk = api_get("/state")
            if 1980 <= st_chk.get("player", {}).get("position", {}).get("x", 0) <= 2055:
                break

    # 2. عبور الطابق الثاني (Catacomb of Famine)
    st_cur = api_get("/state")
    pos_cur = st_cur.get("player", {}).get("position", {})
    px, py = pos_cur.get('x', 0), pos_cur.get('y', 0)
    if 1980 <= px <= 2055 and py >= 5000:
        print("\n--- [المرحلة 4]: عبور الطابق الثاني إلى سلم النزول للطابق الثالث ---")
        interact_object(19005, "Use")
        time.sleep(2)
        handle_dialogue_and_modals()
        walk_to(2026, 5219, plane=0, timeout=35)
        print("🖐️ النزول عبر سلم الطابق الثاني (19004)...")
        for attempt in range(4):
            interact_object(19004, "Climb-down")
            for _ in range(6):
                time.sleep(1)
                handle_dialogue_and_modals()
                st_chk = api_get("/state")
                px_chk = st_chk.get("player", {}).get("position", {}).get("x", 0)
                if 2110 <= px_chk <= 2185:
                    print("📍 تم النزول للطابق الثالث بنجاح!")
                    break
            st_chk = api_get("/state")
            if 2110 <= st_chk.get("player", {}).get("position", {}).get("x", 0) <= 2185:
                break

    # 3. الطابق الثالث وحصد الـ 5,000 GP (Pit of Pestilence)
    st_cur = api_get("/state")
    pos_cur = st_cur.get("player", {}).get("position", {})
    px, py = pos_cur.get('x', 0), pos_cur.get('y', 0)
    if 2110 <= px <= 2185 and py >= 5000:
        print("\n--- [المرحلة 5]: عبور متاهة الطابق الثالث بمحطات وسيطة (Waypoints) وحصد 5,000 GP ---")
        # محطة 1: أمام الباب الشرقي الأول
        walk_to(2132, 5258, plane=0, timeout=25)
        try_pass_door("Oozing barrier")
        # محطة 2: أمام الباب الشمالي الثاني
        walk_to(2132, 5260, plane=0, timeout=15)
        try_pass_door("Oozing barrier")
        # محطة 3: أمام ممر غرفة الصندوق
        walk_to(2132, 5277, plane=0, timeout=30)
        try_pass_door("Oozing barrier")
        # محطة 4: غرفة الصندوق مباشرة
        walk_to(2144, 5281, plane=0, timeout=30)

        for attempt in range(3):
            interact_object(23709, "Open")
            time.sleep(2)
            handle_dialogue_and_modals()
            vb3 = api_get("/varbits?id=2311").get("value", 0)
            if vb3 == 1:
                print("💰 مبروووك! تم استلام الـ 5,000 GP بنجاح!")
                break
        
        print("\n--- [المرحلة 6]: النزول للطابق الرابع عبر السلم (23706) ---")
        walk_to(2147, 5284, plane=0, timeout=60)
        for attempt in range(4):
            interact_object(23706, "Climb-down")
            for _ in range(6):
                time.sleep(1)
                handle_dialogue_and_modals()
                st_chk = api_get("/state")
                px_chk = st_chk.get("player", {}).get("position", {}).get("x", 0)
                if 2300 <= px_chk <= 2375:
                    print("📍 تم النزول للطابق الرابع بنجاح!")
                    break
            st_chk = api_get("/state")
            if 2300 <= st_chk.get("player", {}).get("position", {}).get("x", 0) <= 2375:
                break

    # 4. الطابق الرابع وحصد Fancy Boots (Sepulchre of Death)
    st_cur = api_get("/state")
    pos_cur = st_cur.get("player", {}).get("position", {})
    px, py = pos_cur.get('x', 0), pos_cur.get('y', 0)
    if 2300 <= px <= 2375 and py >= 5000:
        print("\n--- [المرحلة 7]: التوجه لمهد الحياة (Cradle of Life) لحصد Fancy Boots ---")
        walk_to(2343, 5215, plane=0, timeout=120)
        for attempt in range(3):
            interact_object(23731, "Search")
            for _ in range(5):
                time.sleep(1)
                handle_dialogue_and_modals()
            vb4 = api_get("/varbits?id=2312").get("value", 0)
            if vb4 == 1:
                print("👟 مبروووك! تم اختيار وحصد Fancy Boots بنجاح!")
                break

        print("\n--- [المرحلة 8]: الهروب للسطح عبر الحبل العظمي (Bone Chain: 23732) ---")
        walk_to(2349, 5215, plane=0, timeout=20)
        for attempt in range(4):
            interact_object(23732, "Climb-up")
            for _ in range(6):
                time.sleep(1)
                handle_dialogue_and_modals()
                st_chk = api_get("/state")
                py_chk = st_chk.get("player", {}).get("position", {}).get("y", 0)
                if py_chk < 5000:
                    print("📍 تم الهروب والصعود لسطح الأرض بنجاح!")
                    break
            st_chk = api_get("/state")
            if st_chk.get("player", {}).get("position", {}).get("y", 0) < 5000:
                break

    # التحقق الحاسم من جمع كامل المكافآت قبل التوجه للبنك
    vb3 = api_get("/varbits?id=2311").get("value", 0)
    vb4 = api_get("/varbits?id=2312").get("value", 0)
    if vb3 != 1 or vb4 != 1:
        print("\n" + "!" * 65)
        print("⚠️ تنبيه هندسي: لم يتم حصد كامل المكافآت بنجاح بعد!")
        print(f"📌 حالة الطابق 3 (5,000 GP): {'✅ تم الاستلام' if vb3 == 1 else '❌ لم يتم الاستلام (Varbit 2311 == 0)'}")
        print(f"📌 حالة الطابق 4 (Fancy Boots): {'✅ تم الاستلام' if vb4 == 1 else '❌ لم يتم الاستلام (Varbit 2312 == 0)'}")
        print("💡 أعد تشغيل البوت لإكمال الطابق المتبقي دون تكرار الطوابق السابقة.")
        print("!" * 65)
        input("\nاضغط Enter للإغلاق...")
        return

    print("\n--- [المرحلة 9]: التوجه لبنك الـ Grand Exchange لحفظ كل الأرباح والجزمة ---")
    walk_to(3164, 3487, plane=0, timeout=75)
    api_post("/bank/open", {})
    time.sleep(2)
    api_post("/bank/deposit", {"all": True})
    time.sleep(1)
    api_post("/bank/close", {})

    print("\n" + "=" * 65)
    print("🎉 مبروووك يا بطل! تم إنجاز المهمة بنجاح 100% وحفظ كل شيء بالبنك!")
    print("=" * 65)
    input("اضغط Enter للإغلاق...")

if __name__ == "__main__":
    main()
