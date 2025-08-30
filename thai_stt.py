import speech_recognition as sr
import os

def main():
    """
    Captures audio from the microphone and uses OpenAI's Whisper API
    to transcribe it into Thai text.
    """
    # Check for OpenAI API key
    if "OPENAI_API_KEY" not in os.environ:
        print("โปรดตั้งค่า OPENAI_API_KEY ของคุณเป็น environment variable ก่อน")
        print("Please set your OPENAI_API_KEY as an environment variable first.")
        return

    r = sr.Recognizer()
    with sr.Microphone() as source:
        print("กำลังปรับลดเสียงรบกวน... กรุณารอสักครู่")
        # Adjust for ambient noise
        r.adjust_for_ambient_noise(source, duration=2)
        print("พร้อมแล้ว! กรุณาพูดภาษาไทย:")

        try:
            # Listen for audio input
            audio = r.listen(source)
            print("กำลังประมวลผล... กรุณารอสักครู่")

            # Recognize speech using OpenAI Whisper API
            # Forcing Thai language for higher accuracy
            text = r.recognize_whisper_api(audio, language="th")

            print("-" * 50)
            print("ข้อความที่ได้ยินคือ:")
            print(text)
            print("-" * 50)

        except sr.UnknownValueError:
            print("ขออภัยค่ะ ไม่สามารถเข้าใจเสียงที่พูดได้")
        except sr.RequestError as e:
            print(f"เกิดข้อผิดพลาดในการเชื่อมต่อกับบริการของ OpenAI; {e}")
        except Exception as e:
            print(f"เกิดข้อผิดพลาดที่ไม่คาดคิด: {e}")

if __name__ == "__main__":
    main()
