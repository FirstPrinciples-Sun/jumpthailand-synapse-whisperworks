from fastapi import FastAPI, File, UploadFile, HTTPException
import uvicorn
import json
import io
from vosk import Model, KaldiRecognizer
from pydub import AudioSegment
from pythainlp.tokenize import word_tokenize
from pythainlp.tag import pos_tag

# Load the Vosk model once when the server starts.
# Ensure you have downloaded the Thai model and placed it in a 'model-th' directory.
MODEL_PATH = "model-th"
try:
    model = Model(MODEL_PATH)
    print("✅ Vosk model for Thai language loaded successfully.")
except Exception as e:
    model = None
    print(f"❌ Error loading Vosk model: {e}")

app = FastAPI(
    title="Whisper Works API",
    description="Backend service with NLP capabilities, powered by AIS 5G & Edge Compute.",
    version="1.1.0"
)

def extract_keywords(text: str) -> list[str]:
    if not text:
        return []
    
    words = word_tokenize(text, engine="newmm")
    pos_tags = pos_tag(words, engine="orchid_ud")
    keyword_tags = {"NOUN", "PROPN", "VERB"}
    keywords = [word for word, tag in pos_tags if tag in keyword_tags]
    
    return keywords

@app.post("/transcribe")
async def transcribe_audio(audio_file: UploadFile = File(...)):
    if not model:
        raise HTTPException(status_code=503, detail="AI Model is not available.")

    print(f"Processing file: '{audio_file.filename}'...")
    audio_data = await audio_file.read()

    try:
        # pydub can read raw PCM if we specify the parameters
        audio_segment = AudioSegment(
            data=audio_data,
            sample_width=2, # 16-bit PCM
            frame_rate=16000,
            channels=1
        )
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Could not process audio file: {e}")
    
    pcm_data = audio_segment.raw_data
    recognizer = KaldiRecognizer(model, 16000)
    recognizer.AcceptWaveform(pcm_data)
    result_json = recognizer.FinalResult()
    
    result_dict = json.loads(result_json)
    transcribed_text = result_dict.get("text", "")

    keywords = extract_keywords(transcribed_text)
    
    print(f"Full Transcription: '{transcribed_text}'")
    print(f"Extracted Keywords: {keywords}")

    return {
        "full_text": transcribed_text,
        "keywords": keywords
    }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)