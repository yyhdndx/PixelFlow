"""Real FastAPI/Java HTTP integration; replace only the paid model, never business data."""
import sys
import uvicorn
from pixelflow_intelligence.api.app import create_app
from pixelflow_intelligence.common.settings import Settings
from pixelflow_intelligence.clients.deepseek import DeepSeekAnswer


def answer(self, *, question, history, sources, deadline):
    return "作品建议：" + sources[0]["excerpt"] + "〔1〕"


if __name__ == "__main__":
    DeepSeekAnswer.__call__ = answer
    app = create_app(Settings(database=sys.argv[3], java_url="http://127.0.0.1:" + sys.argv[2],
                              deepseek_api_key="integration-test-placeholder"))
    uvicorn.run(app, host="127.0.0.1", port=int(sys.argv[1]), log_level="warning")
