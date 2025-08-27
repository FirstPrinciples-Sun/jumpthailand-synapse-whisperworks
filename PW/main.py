#!/usr/bin/env python3
"""
WhisperWorks FastAPI Backend
Enhanced version with better error handling, logging, and performance optimizations
"""

import asyncio
import json
import logging
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import List, Optional, Dict, Any
import traceback

import uvicorn
from fastapi import FastAPI, File, UploadFile, HTTPException, BackgroundTasks, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from vosk import Model, KaldiRecognizer
from pydub import AudioSegment
from pythainlp.tokenize import word_tokenize
from pythainlp.tag import pos_tag

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.FileHandler('whisperworks.log'),
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

# Global variables
model: Optional[Model] = None
MODEL_PATH = "model-th"
SUPPORTED_LANGUAGES = ["th", "en"]
MAX_FILE_SIZE = 50 * 1024 * 1024  # 50MB
ALLOWED_AUDIO_FORMATS = {'.wav', '.pcm', '.mp3', '.m4a', '.ogg', '.flac'}

# Performance settings
RECOGNIZER_POOL_SIZE = 3
recognizer_pool = []

class ServerConfig:
    """Server configuration"""
    MAX_WORKERS = 4
    TIMEOUT_SECONDS = 60
    UPLOAD_DIR = Path("uploads")
    TEMP_DIR = Path("temp")
    
    def __init__(self):
        self.UPLOAD_DIR.mkdir(exist_ok=True)
        self.TEMP_DIR.mkdir(exist_ok=True)

config = ServerConfig()

# Pydantic models
class TranscriptionRequest(BaseModel):
    """Request model for transcription"""
    language: str = Field(default="th", description="Language code (th, en)")
    model_type: str = Field(default="vosk-thai", description="Model type to use")
    include_timestamps: bool = Field(default=False, description="Include word timestamps")
    confidence_threshold: float = Field(default=0.0, description="Minimum confidence threshold")

class TranscriptionResponse(BaseModel):
    """Response model for transcription results"""
    full_text: str = Field(..., description="Complete transcribed text")
    keywords: List[str] = Field(..., description="Extracted keywords")
    confidence: Optional[float] = Field(None, description="Overall confidence score")
    processing_time: Optional[float] = Field(None, description="Processing time in seconds")
    language: Optional[str] = Field(None, description="Detected/used language")
    word_count: Optional[int] = Field(None, description="Number of words")
    timestamps: Optional[List[Dict[str, Any]]] = Field(None, description="Word timestamps")

class ServerStatus(BaseModel):
    """Server status response"""
    status: str
    version: str
    model_loaded: bool
    supported_languages: List[str]
    uptime_seconds: float
    total_requests: int
    active_requests: int

class HealthResponse(BaseModel):
    """Health check response"""
    status: str
    timestamp: float
    model_ready: bool

# Application state
app_state = {
    "start_time": time.time(),
    "total_requests": 0,
    "active_requests": 0,
    "model_loaded": False
}

async def load_model():
    """Load the Vosk model asynchronously"""
    global model, recognizer_pool
    
    try:
        logger.info(f"Loading Vosk model from {MODEL_PATH}...")
        
        model_path = Path(MODEL_PATH)
        if not model_path.exists():
            raise FileNotFoundError(f"Model directory not found: {MODEL_PATH}")
        
        model = Model(str(model_path))
        
        # Initialize recognizer pool
        recognizer_pool = [
            KaldiRecognizer(model, 16000) 
            for _ in range(RECOGNIZER_POOL_SIZE)
        ]
        
        app_state["model_loaded"] = True
        logger.info("✅ Vosk model for Thai language loaded successfully")
        logger.info(f"Created {len(recognizer_pool)} recognizers in pool")
        
    except Exception as e:
        logger.error(f"❌ Error loading Vosk model: {e}")
        logger.error(traceback.format_exc())
        app_state["model_loaded"] = False
        raise

def get_recognizer() -> KaldiRecognizer:
    """Get a recognizer from the pool"""
    if not recognizer_pool:
        if not model:
            raise HTTPException(status_code=503, detail="AI Model is not available")
        return KaldiRecognizer(model, 16000)
    
    recognizer = recognizer_pool.pop()
    # Reset recognizer state
    recognizer.Reset()
    return recognizer

def return_recognizer(recognizer: KaldiRecognizer):
    """Return a recognizer to the pool"""
    if len(recognizer_pool) < RECOGNIZER_POOL_SIZE:
        recognizer_pool.append(recognizer)

def extract_keywords(text: str, language: str = "th") -> List[str]:
    """Extract keywords from text with improved algorithm"""
    if not text.strip():
        return []
    
    try:
        if language == "th":
            # Thai language processing
            words = word_tokenize(text, engine="newmm")
            pos_tags = pos_tag(words, engine="orchid_ud")
            
            # Include more POS tags for better keyword extraction
            keyword_tags = {"NOUN", "PROPN", "VERB", "ADJ"}
            keywords = []
            
            for word, tag in pos_tags:
                if tag in keyword_tags and len(word.strip()) > 1:
                    keywords.append(word.strip())
            
            # Remove duplicates while preserving order
            seen = set()
            unique_keywords = []
            for keyword in keywords:
                if keyword.lower() not in seen:
                    seen.add(keyword.lower())
                    unique_keywords.append(keyword)
                    
            return unique_keywords[:10]  # Limit to top 10 keywords
            
        else:
            # Simple English keyword extraction
            words = text.split()
            # Filter out common stop words and short words
            stop_words = {"the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for", "of", "with", "by"}
            keywords = [word for word in words 
                       if len(word) > 3 and word.lower() not in stop_words]
            return list(set(keywords))[:10]
            
    except Exception as e:
        logger.warning(f"Keyword extraction failed: {e}")
        # Fallback to simple word splitting
        return text.split()[:10]

def validate_audio_file(file: UploadFile) -> None:
    """Validate uploaded audio file"""
    # Check file size
    if hasattr(file, 'size') and file.size and file.size > MAX_FILE_SIZE:
        raise HTTPException(
            status_code=413, 
            detail=f"File too large. Maximum size: {MAX_FILE_SIZE // (1024*1024)}MB"
        )
    
    # Check file extension
    if file.filename:
        file_ext = Path(file.filename).suffix.lower()
        if file_ext not in ALLOWED_AUDIO_FORMATS and file_ext != '.pcm':
            raise HTTPException(
                status_code=400,
                detail=f"Unsupported file format. Supported: {', '.join(ALLOWED_AUDIO_FORMATS)}"
            )

async def process_audio_segment(audio_data: bytes, language: str = "th") -> dict:
    """Process audio data and return transcription"""
    recognizer = None
    processing_start = time.time()
    
    try:
        recognizer = get_recognizer()
        
        # Convert raw PCM to AudioSegment
        audio_segment = AudioSegment(
            data=audio_data,
            sample_width=2,  # 16-bit PCM
            frame_rate=16000,
            channels=1
        )
        
        # Get raw PCM data
        pcm_data = audio_segment.raw_data
        
        # Process in chunks for better memory management
        chunk_size = 4096
        for i in range(0, len(pcm_data), chunk_size):
            chunk = pcm_data[i:i + chunk_size]
            recognizer.AcceptWaveform(chunk)
        
        # Get final result
        result_json = recognizer.FinalResult()
        result_dict = json.loads(result_json)
        
        transcribed_text = result_dict.get("text", "").strip()
        confidence = result_dict.get("conf", 0.0)
        
        # Extract keywords
        keywords = extract_keywords(transcribed_text, language)
        
        processing_time = time.time() - processing_start
        
        logger.info(f"Transcription completed in {processing_time:.2f}s")
        logger.info(f"Text: '{transcribed_text}'")
        logger.info(f"Keywords: {keywords}")
        logger.info(f"Confidence: {confidence}")
        
        return {
            "full_text": transcribed_text,
            "keywords": keywords,
            "confidence": confidence,
            "processing_time": processing_time,
            "language": language,
            "word_count": len(transcribed_text.split()) if transcribed_text else 0
        }
        
    except Exception as e:
        logger.error(f"Audio processing failed: {e}")
        logger.error(traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"Audio processing failed: {str(e)}")
    
    finally:
        if recognizer:
            return_recognizer(recognizer)

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager"""
    # Startup
    logger.info("🚀 Starting WhisperWorks API server...")
    try:
        await load_model()
        logger.info("✅ Server startup completed successfully")
    except Exception as e:
        logger.error(f"❌ Server startup failed: {e}")
        raise
    
    yield
    
    # Shutdown
    logger.info("🛑 Shutting down WhisperWorks API server...")
    # Cleanup code here if needed
    logger.info("✅ Server shutdown completed")

# Create FastAPI app
app = FastAPI(
    title="WhisperWorks API",
    description="Advanced Backend service with NLP capabilities, powered by AIS 5G & Edge Compute",
    version="2.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan
)

# Add middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.add_middleware(GZipMiddleware, minimum_size=1000)

# Request counter middleware
@app.middleware("http")
async def request_counter_middleware(request, call_next):
    app_state["active_requests"] += 1
    app_state["total_requests"] += 1
    
    try:
        response = await call_next(request)
        return response
    finally:
        app_state["active_requests"] -= 1

# API Endpoints
@app.get("/", response_model=dict)
async def root():
    """Root endpoint"""
    return {
        "message": "WhisperWorks API Server",
        "version": "2.0.0",
        "status": "running",
        "model_loaded": app_state["model_loaded"]
    }

@app.get("/health", response_model=HealthResponse)
async def health_check():
    """Health check endpoint"""
    return HealthResponse(
        status="healthy" if app_state["model_loaded"] else "degraded",
        timestamp=time.time(),
        model_ready=app_state["model_loaded"]
    )

@app.get("/ping")
async def ping():
    """Simple ping endpoint"""
    return {"status": "pong", "timestamp": time.time()}

@app.get("/status", response_model=ServerStatus)
async def get_server_status():
    """Detailed server status"""
    uptime = time.time() - app_state["start_time"]
    
    return ServerStatus(
        status="running",
        version="2.0.0",
        model_loaded=app_state["model_loaded"],
        supported_languages=SUPPORTED_LANGUAGES,
        uptime_seconds=uptime,
        total_requests=app_state["total_requests"],
        active_requests=app_state["active_requests"]
    )

@app.post("/transcribe", response_model=TranscriptionResponse)
async def transcribe_audio(
    background_tasks: BackgroundTasks,
    audio_file: UploadFile = File(..., description="Audio file to transcribe"),
    language: str = Query("th", description="Language code (th, en)"),
    include_timestamps: bool = Query(False, description="Include word timestamps"),
    confidence_threshold: float = Query(0.0, description="Minimum confidence threshold")
):
    """
    Transcribe audio file to text with keyword extraction
    """
    if not app_state["model_loaded"]:
        raise HTTPException(status_code=503, detail="AI Model is not available")
    
    # Validate input
    validate_audio_file(audio_file)
    
    if language not in SUPPORTED_LANGUAGES:
        raise HTTPException(
            status_code=400, 
            detail=f"Unsupported language: {language}. Supported: {SUPPORTED_LANGUAGES}"
        )
    
    logger.info(f"Processing transcription request for file: '{audio_file.filename}' (language: {language})")
    
    try:
        # Read audio data
        audio_data = await audio_file.read()
        
        if len(audio_data) == 0:
            raise HTTPException(status_code=400, detail="Audio file is empty")
        
        logger.info(f"Audio file size: {len(audio_data)} bytes")
        
        # Process audio
        result = await process_audio_segment(audio_data, language)
        
        # Filter by confidence threshold if specified
        if confidence_threshold > 0 and result.get("confidence", 0) < confidence_threshold:
            logger.warning(f"Transcription confidence {result.get('confidence', 0)} below threshold {confidence_threshold}")
        
        # Create response
        response = TranscriptionResponse(**result)
        
        # Background task for cleanup
        background_tasks.add_task(cleanup_temp_files)
        
        logger.info("✅ Transcription request completed successfully")
        return response
        
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"❌ Transcription request failed: {e}")
        logger.error(traceback.format_exc())
        raise HTTPException(status_code=500, detail=f"Internal server error: {str(e)}")

async def cleanup_temp_files():
    """Background task to cleanup temporary files"""
    try:
        # Add cleanup logic here if needed
        pass
    except Exception as e:
        logger.warning(f"Cleanup task failed: {e}")

@app.exception_handler(Exception)
async def general_exception_handler(request, exc):
    """Global exception handler"""
    logger.error(f"Unhandled exception: {exc}")
    logger.error(traceback.format_exc())
    
    return JSONResponse(
        status_code=500,
        content={
            "detail": "Internal server error",
            "error_type": type(exc).__name__,
            "timestamp": time.time()
        }
    )

# Main entry point
if __name__ == "__main__":
    logger.info("🎯 Starting WhisperWorks FastAPI Server")
    
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8000,
        reload=False,  # Set to True only in development
        workers=1,     # Use 1 worker due to model loading
        log_level="info",
        access_log=True,
        server_header=False,
        date_header=False
    )