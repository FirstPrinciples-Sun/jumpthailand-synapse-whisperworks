import speech_recognition as sr
import os
import time
from collections import deque

class RealTimeAugmenter:
    """
    คลาสสำหรับจัดการ Real-time Augmenter ทั้งหมด
    เพื่อโครงสร้างโค้ดที่เป็นระเบียบและง่ายต่อการขยาย
    """
    # --- 1. การตั้งค่า (Configuration) ---
    def __init__(self):
        # ตั้งค่าการวิเคราะห์
        self.FILLER_WORDS = ["เอ่อ", "อ่า", "แบบว่า", "คือว่า", "ก็คือ", "แบบ"]
        self.KEYWORD_TRIGGERS = {
            "ราคา": "ข้อมูลสำคัญ: ราคาเริ่มต้นที่ 50,000 บาท พร้อมโปรโมชั่นลด 10%",
            "คู่แข่ง": "ข้อมูลสำคัญ: คู่แข่งหลักคือบริษัท A และ B จุดแข็งของเราคือบริการหลังการขาย",
            "โปรเจกต์ X": "ข้อมูลสำคัญ: สถานะโปรเจกต์ X ตอนนี้คืบหน้าไป 75% และจะเสร็จสิ้นในเดือนหน้า"
        }
        # ตั้งค่าความเร็วในการพูด (WPM)
        self.WPM_SLOW_THRESHOLD = 110
        self.WPM_FAST_THRESHOLD = 160
        self.PACING_WINDOW_SECONDS = 5
        
        # ตัวแปรสถานะ (State)
        self.filler_word_counts = {word: 0 for word in self.FILLER_WORDS}
        self.pacing_word_count = 0
        self.pacing_start_time = time.time()
        self.spoken_text_history = set()
        self.live_transcript = deque(maxlen=3) # เก็บประโยคล่าสุด 3 ประโยค
        self.triggered_keywords_log = deque(maxlen=5) # เก็บ Keyword ที่เจอ 5 รายการล่าสุด

        # ตั้งค่า Speech Recognition
        self.recognizer = sr.Recognizer()
        self.microphone = sr.Microphone()
        # *ปรับค่าเพื่อให้ตอบสนองเร็วขึ้น*
        self.recognizer.pause_threshold = 0.6      # ลดเวลาที่รอหลังพูดจบ (ค่าปกติ 0.😎
        self.recognizer.non_speaking_duration = 0.4 # ลดความเงียบที่ต้องมีก่อนเริ่มพูด (ค่าปกติ 0.5)

    def _clear_screen(self):
        """ล้างหน้าจอ Terminal"""
        os.system('cls' if os.name == 'nt' else 'clear')

    def _display_dashboard(self):
        """แสดงผลข้อมูลทั้งหมดบนหน้าจอ"""
        self._clear_screen()
        print("="*60)
        print("--- 🚀 The Pro Co-pilot: Real-time Augmenter 🚀 ---")
        print("สถานะ: กำลังรับฟัง... (กด Ctrl+C เพื่อหยุด)")
        print("="*60)

        # --- Live Transcript ---
        print("\n📜 Live Transcript (สิ่งที่คุณพูดล่าสุด):")
        if not self.live_transcript:
            print("   ...")
        else:
            for i, text in enumerate(self.live_transcript):
                print(f"   - {text}")
        
        # --- Filler Word Counter ---
        print("\n📊 ตัวนับคำฟุ่มเฟือย (Filler Words):")
        total_fillers = sum(self.filler_word_counts.values())
        if total_fillers > 0:
            for word, count in self.filler_word_counts.items():
                if count > 0:
                    print(f"   - {word}: {count} ครั้ง")
        else:
            print("   ยอดเยี่ยม! ยังไม่พบคำฟุ่มเฟือย")
            
        # --- Pacing Feedback ---
        print("\n🏃‍♂️ ความเร็วในการพูด (Pacing):")
        elapsed_time = time.time() - self.pacing_start_time
        if elapsed_time > 1 and self.pacing_word_count > 0:
            wpm = (self.pacing_word_count / elapsed_time) * 60
            feedback = "✅ กำลังดี"
            if wpm < self.WPM_SLOW_THRESHOLD:
                feedback = "🐢 ช้าไป"
            elif wpm > self.WPM_FAST_THRESHOLD:
                feedback = "🚀 เร็วไป"
            print(f"   ปัจจุบัน: {int(wpm)} WPM ({feedback})")
        else:
            print("   กำลังรอข้อมูล...")

        # --- Keyword Trigger Log ---
        print("\n🔔 Keyword ที่ตรวจพบ:")
        if not self.triggered_keywords_log:
            print("   ยังไม่พบ Keyword ที่ตั้งค่าไว้")
        else:
             for log in self.triggered_keywords_log:
                print(log)

        print("\n"+"-"*60)

    def _process_text(self, text):
        """วิเคราะห์ข้อความที่ได้รับมาใหม่"""
        words = text.split()
        if not words:
            return

        self.live_transcript.append(text)
        
        # 1. นับคำฟุ่มเฟือย
        for word in words:
            if word in self.FILLER_WORDS:
                self.filler_word_counts[word] += 1
        
        # 2. เพิ่มจำนวนคำสำหรับคำนวณความเร็ว
        self.pacing_word_count += len(words)

        # 3. ตรวจจับ Keyword
        for keyword, info in self.KEYWORD_TRIGGERS.items():
            if keyword in text and keyword not in self.spoken_text_history:
                log_message = f"   🎯 '{keyword}': {info}"
                self.triggered_keywords_log.append(log_message)
                self.spoken_text_history.add(keyword)

    def _audio_callback(self, recognizer, audio):
        """ฟังก์ชันที่จะถูกเรียกใช้เมื่อตรวจจับเสียงพูดได้"""
        try:
            text = recognizer.recognize_google(audio, language="th-TH")
            self._process_text(text)
        except sr.UnknownValueError:
            pass # ไม่ต้องทำอะไรถ้าไม่เข้าใจเสียง
        except sr.RequestError as e:
            error_log = f"   [!] เกิดข้อผิดพลาด: {e}"
            self.triggered_keywords_log.append(error_log)

    def run(self):
        """เริ่มการทำงานของโปรแกรม"""
        print("กำลังเตรียมตัว... กรุณาเงียบสักครู่เพื่อปรับค่าไมโครโฟน")
        with self.microphone as source:
            self.recognizer.adjust_for_ambient_noise(source, duration=2)
        print("เตรียมตัวเรียบร้อย! เริ่มพูดได้เลย")
        time.sleep(1)

        stop_listening = self.recognizer.listen_in_background(self.microphone, self._audio_callback)
        
        try:
            while True:
                self._display_dashboard()
                # รีเซ็ตการนับความเร็วตามรอบเวลา
                if time.time() - self.pacing_start_time > self.PACING_WINDOW_SECONDS:
                    self.pacing_start_time = time.time()
                    self.pacing_word_count = 0
                time.sleep(0.5) # อัปเดตหน้าจอทุกครึ่งวินาที
        except KeyboardInterrupt:
            print("\nกำลังหยุดการทำงาน...")
            stop_listening(wait_for_stop=False)
            print("ปิดโปรแกรมเรียบร้อย ขอบคุณที่ใช้งานครับ!")

# --- ส่วนของการรันโปรแกรม ---
if __name__ == "__main__":
    augmenter = RealTimeAugmenter()
    augmenter.run()