#!/usr/bin/env python3
import speech_recognition as sr
import os
import time
import argparse
import threading
from collections import deque
from datetime import datetime
import json
import re

# (ทางเลือก) Thai NLP
try:
    from pythainlp import word_tokenize
    from pythainlp.tokenize import sent_tokenize
    HAS_THAI = True
except Exception:
    HAS_THAI = False

# (ทางเลือก) Gemini LLM
try:
    import google.generativeai as genai
    HAS_GEMINI = True
except Exception:
    HAS_GEMINI = False


class ThaiMeetingSummarizer:
    """
    สรุปประเด็นจาก transcript แบบง่าย (offline)
    - ดึงหัวข้อหลัก (topics) จากคำที่พบบ่อย
    - ดึงการตัดสินใจ (decisions) จากประโยค trigger
    - ดึง Action items (assignee, task, due) แบบ heuristic
    - ดึงความเสี่ยง/ติดตาม (risks_or_followups)
    """
    def __init__(self, language="th-TH"):
        self.language = language
        self.utterances = []  # list of (timestamp, text)

        self.topic_stopwords = set([
            "ครับ","ค่ะ","คะ","เรา","เขา","ที่","ว่า","และ","หรือ","ก็","คือ","มัน","ได้","ๆ","นะ","ค่ะ","ครับ",
            "เอ่อ","อ่า","แบบว่า","คือว่า","ก็คือ","แบบ"
        ])

        # คำสัญญาณ (ไทย)
        self.decision_triggers = ["ตัดสินใจ", "อนุมัติ", "ยืนยัน", "สรุปว่า", "ข้อสรุป", "ตกลงว่า"]
        self.action_triggers   = ["มอบหมาย", "รับผิดชอบ", "ต้องทำ", "จะทำ", "ดำเนินการ", "ภายใน", "ก่อน", "ส่ง", "ติดตาม", "เดดไลน์", "กำหนดส่ง"]
        self.risk_triggers     = ["ความเสี่ยง", "เสี่ยง", "ปัญหา", "ติดขัด", "ค้าง", "ยังไม่เสร็จ", "รอ", "อาจ", "ขึ้นอยู่กับ", "บล็อค"]

        # รูปแบบหา assignee/เวลาแบบง่าย
        self.assignee_patterns = [
            r"(คุณ[^\s]+)", r"(พี่[^\s]+)", r"(นาย[^\s]+)", r"(นาง[^\s]+)", r"(นางสาว[^\s]+)"
        ]
        # วันที่/เดดไลน์แบบง่าย (ไทย/ตัวเลข)
        self.due_patterns = [
            r"(ภายใน\s*\d+\s*(วัน|สัปดาห์|เดือน))",
            r"(ก่อน\s*วันที่\s*\d{1,2}/\d{1,2}/\d{2,4})",
            r"(ภายใน\s*สัปดาห์นี้|ภายใน\s*เดือนนี้|สิ้นเดือนนี้|สัปดาห์หน้า|เดือนหน้า)"
        ]

    def add_utterance(self, ts, text):
        self.utterances.append((ts, text))

    def _thai_sentences(self, text):
        if HAS_THAI:
            try:
                return [s.strip() for s in sent_tokenize(text) if s.strip()]
            except Exception:
                pass
        # fallback
        parts = re.split(r"[\.!\?\n]|[ ]{2,}|[|]", text)
        return [p.strip() for p in parts if p.strip()]

    def _thai_words(self, text):
        if HAS_THAI:
            try:
                return [w for w in word_tokenize(text) if w.strip()]
            except Exception:
                pass
        # fallback: แยกด้วยช่องว่าง
        return [w for w in re.split(r"\s+", text) if w.strip()]

    def _extract_topics(self, all_text, topn=5):
        words = self._thai_words(all_text)
        freq = {}
        for w in words:
            if w in self.topic_stopwords:
                continue
            if len(w) < 2:
                continue
            freq[w] = freq.get(w, 0) + 1
        topics = sorted(freq.items(), key=lambda x: x[1], reverse=True)[:topn]
        return [t[0] for t in topics]

    def _match_any(self, text, triggers):
        return any(t in text for t in triggers)

    def _extract_assignee(self, sentence):
        for pat in self.assignee_patterns:
            m = re.search(pat, sentence)
            if m:
                return m.group(1)
        return "ไม่ระบุ"

    def _extract_due(self, sentence):
        for pat in self.due_patterns:
            m = re.search(pat, sentence)
            if m:
                return m.group(1)
        return "ไม่ระบุ"

    def summarize_offline(self):
        # รวมข้อความทั้งหมด
        all_text = "\n".join([t for _, t in self.utterances]).strip()
        topics = self._extract_topics(all_text, topn=6)

        decisions = []
        action_items = []
        risks = []
        highlights = []

        # ประมวลผลทีละประโยค
        for ts, text in self.utterances:
            for sent in self._thai_sentences(text):
                if self._match_any(sent, self.decision_triggers):
                    decisions.append(sent)
                if self._match_any(sent, self.action_triggers):
                    action_items.append({
                        "assignee": self._extract_assignee(sent),
                        "task": sent,
                        "due": self._extract_due(sent)
                    })
                if self._match_any(sent, self.risk_triggers):
                    risks.append(sent)

        # ไฮไลต์: เลือกประโยคยาว/มีคำสำคัญ
        for ts, text in self.utterances:
            if len(text) >= 25 and (self._match_any(text, self.decision_triggers + self.action_triggers) or len(text) > 60):
                highlights.append(text)
        highlights = highlights[:8]

        summary = {
            "summary": "สรุปอัตโนมัติแบบออฟไลน์ (heuristic) จากการสนทนา",
            "topics": topics,
            "decisions": decisions[:10],
            "action_items": action_items[:20],
            "risks_or_followups": risks[:10],
            "highlights": highlights
        }
        return summary

    def summarize_with_gemini(self):
        api_key = os.environ.get("GOOGLE_API_KEY")
        if not (HAS_GEMINI and api_key):
            return None
        genai.configure(api_key==api_key)
        model = genai.GenerativeModel(
            "gemini-1.5-flash",
            generation_config={
                "temperature": 0.2,
                "top_p": 0.9,
                "max_output_tokens": 2048,
                "response_mime_type": "application/json",
            },
        )
        all_text = "\n".join([t for _, t in self.utterances]).strip()
        prompt = (
            "คุณเป็นเลขานุการการประชุม ช่วยสรุปข้อความต่อไปนี้เป็นภาษาไทย "
            "และส่งคืน JSON กับคีย์: summary, topics, decisions, "
            "action_items (assignee, task, due), risks_or_followups, highlights. "
            "หากไม่พบเส้นตายให้ใส่ 'ไม่ระบุ'.\n\n"
            f"{all_text}"
        )
        try:
            resp = model.generate_content(prompt)
            data = json.loads(resp.text)
            return data
        except Exception:
            return None

    def summarize(self, prefer_gemini=True):
        if prefer_gemini:
            data = self.summarize_with_gemini()
            if data:
                return data
        return self.summarize_offline()


class RealTimeAugmenter:
    """
    ฟังเสียงแบบเรียลไทม์ -> ถอดเสียงด้วย Google -> แดชบอร์ด -> สรุปการประชุม
    (ตัดฟังก์ชัน: คำฟุ่มเฟือย, WPM, Keyword ออกแล้ว)
    """
    def __init__(self,
                 language="th-TH",
                 mic_index=None,
                 summarize_interval=30):
        # การตั้งค่า
        self.language = language
        self.mic_index = mic_index
        self.SUMMARIZE_INTERVAL = summarize_interval

        # สถานะที่เกี่ยวข้องกับการแสดงผล
        self.live_transcript = deque(maxlen=5)
        self.lock = threading.Lock()
        self.all_utterances = []  # list of (ts, text)
        self.last_text = None

        # STT
        self.recognizer = sr.Recognizer()
        self.microphone = sr.Microphone(device_index=self.mic_index)
        self.recognizer.pause_threshold = 0.6
        self.recognizer.non_speaking_duration = 0.4
        self.google_key = os.environ.get("GOOGLE_SPEECH_RECOGNITION_API_KEY")

        # Summarizer
        self.summarizer = ThaiMeetingSummarizer(language=self.language)
        self.last_summary_time = 0
        self.live_summary_cache = None

        # Output
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.out_dir = "output_meeting"
        os.makedirs(self.out_dir, exist_ok=True)
        self.base_name = f"meeting_{ts}"
        self.out_transcript = os.path.join(self.out_dir, f"{self.base_name}.transcript.txt")
        self.out_summary_json = os.path.join(self.out_dir, f"{self.base_name}.summary.json")
        self.out_summary_txt = os.path.join(self.out_dir, f"{self.base_name}.summary.txt")

    # ---------- UI ----------
    def _clear_screen(self):
        os.system('cls' if os.name == 'nt' else 'clear')

    def _display_dashboard(self):
        self._clear_screen()
        print("="*70)
        print("--- 🚀 Real-time Meeting Summarizer (Google STT) 🚀 ---")
        print("สถานะ: กำลังรับฟัง... (กด Ctrl+C เพื่อหยุด)")
        print("="*70)

        # Live transcript
        print("\n📜 Live Transcript:")
        with self.lock:
            if not self.live_transcript:
                print("   ...")
            else:
                for t in self.live_transcript:
                    print(f"   - {t}")

        # Live summary (cache)
        print("\n🧭 Live Meeting Summary (ล่าสุด):")
        if self.live_summary_cache:
            s = self.live_summary_cache
            topics = ", ".join(s.get("topics", [])[:5]) or "-"
            decisions_n = len(s.get("decisions", []))
            actions_n = len(s.get("action_items", []))
            risks_n = len(s.get("risks_or_followups", []))
            print(f"   • หัวข้อหลัก: {topics}")
            print(f"   • การตัดสินใจ: {decisions_n} รายการ")
            print(f"   • Action items: {actions_n} รายการ")
            print(f"   • ความเสี่ยง/ติดตาม: {risks_n} รายการ")
        else:
            print("   ...")

        print("\n" + "-"*70)

    # ---------- Processing ----------
    def _process_text(self, text):
        text = text.strip()
        if not text:
            return
        # กันข้อความซ้ำติดๆ กัน
        if self.last_text and text == self.last_text:
            return
        self.last_text = text

        now = time.time()
        with self.lock:
            self.live_transcript.append(text)
            self.all_utterances.append((now, text))
            self.summarizer.add_utterance(now, text)

    # ---------- Callback ----------
    def _audio_callback(self, recognizer, audio):
        try:
            result = recognizer.recognize_google(audio, language=self.language, key=self.google_key)
            self._process_text(result)
        except sr.UnknownValueError:
            pass
        except sr.RequestError as e:
            with self.lock:
                # แสดงเป็นบรรทัดแจ้งเตือนใน transcript เพื่อให้เห็นในหน้าจอ
                self.live_transcript.append(f"[ข้อผิดพลาดเชื่อมต่อ: {e}]")

    # ---------- Save ----------
    def _save_results(self, final_summary):
        # Transcript
        with open(self.out_transcript, "w", encoding="utf-8") as f:
            with self.lock:
                for ts, text in self.all_utterances:
                    rel = ts - (self.all_utterances[0][0] if self.all_utterances else ts)
                    f.write(f"[+{rel:0.1f}s] {text}\n")
        # Summary JSON
        with open(self.out_summary_json, "w", encoding="utf-8") as f:
            json.dump(final_summary, f, ensure_ascii=False, indent=2)
        # Summary TXT (อ่านง่าย)
        lines = []
        lines.append("สรุปการประชุม (อัตโนมัติ)")
        lines.append("")
        if final_summary.get("summary"):
            lines.append(f"- สรุปย่อ: {final_summary['summary']}")
        if final_summary.get("topics"):
            lines.append("- หัวข้อหลัก:")
            for t in final_summary["topics"]:
                lines.append(f"  • {t}")
        if final_summary.get("decisions"):
            lines.append("- การตัดสินใจ:")
            for d in final_summary["decisions"]:
                lines.append(f"  • {d}")
        if final_summary.get("action_items"):
            lines.append("- Action items:")
            for a in final_summary["action_items"]:
                lines.append(f"  • ผู้รับผิดชอบ: {a.get('assignee','ไม่ระบุ')}, งาน: {a.get('task','')}, เส้นตาย: {a.get('due','ไม่ระบุ')}")
        if final_summary.get("risks_or_followups"):
            lines.append("- ความเสี่ยง/ประเด็นติดตาม:")
            for r in final_summary["risks_or_followups"]:
                lines.append(f"  • {r}")
        if final_summary.get("highlights"):
            lines.append("- ไฮไลต์:")
            for h in final_summary["highlights"]:
                lines.append(f"  • {h}")
        with open(self.out_summary_txt, "w", encoding="utf-8") as f:
            f.write("\n".join(lines))

        print(f"\nบันทึกไฟล์เรียบร้อย:")
        print(f"- Transcript: {self.out_transcript}")
        print(f"- Summary JSON: {self.out_summary_json}")
        print(f"- Summary TXT: {self.out_summary_txt}")

    # ---------- Run ----------
    def run(self):
        print("กำลังเตรียมไมโครโฟน... กรุณาเงียบสักครู่เพื่อตั้งค่า Ambient Noise")
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=2.0)
        print("พร้อมแล้ว! เริ่มพูดได้เลย")

        stop_listening = self.recognizer.listen_in_background(self.microphone, self._audio_callback)

        try:
            while True:
                # อัปเดตสรุปเป็นระยะ
                if time.time() - self.last_summary_time > self.SUMMARIZE_INTERVAL:
                    self.live_summary_cache = self.summarizer.summarize(prefer_gemini=True)
                    self.last_summary_time = time.time()

                self._display_dashboard()
                time.sleep(0.6)
        except KeyboardInterrupt:
            print("\nกำลังหยุดการทำงาน...")
            stop_listening(wait_for_stop=False)

            # สรุปครั้งสุดท้าย
            final_summary = self.summarizer.summarize(prefer_gemini=True)
            self._save_results(final_summary)
            print("ปิดโปรแกรมเรียบร้อย ขอบคุณที่ใช้งานครับ!")


def list_microphones():
    for i, name in enumerate(sr.Microphone.list_microphone_names()):
        print(f"[{i}] {name}")


def main():
    parser = argparse.ArgumentParser(description="Real-time Meeting Capturer & Summarizer (Google STT)")
    parser.add_argument("--language", default="th-TH", help="รหัสภาษา (เช่น th-TH, en-US)")
    parser.add_argument("--mic", type=int, default=None, help="index ของไมโครโฟน (ดูจาก --list-mics)")
    parser.add_argument("--list-mics", action="store_true", help="แสดงรายการไมค์และออกจากโปรแกรม")
    parser.add_argument("--summarize-interval", type=int, default=30, help="ความถี่ (วินาที) ในการอัปเดตสรุปบนหน้าจอ")
    args = parser.parse_args()

    if args.list_mics:
        list_microphones()
        return

    augmenter = RealTimeAugmenter(
        language=args.language,
        mic_index=args.mic,
        summarize_interval=args.summarize_interval
    )
    augmenter.run()


if __name__ == "__main__":
    main()
