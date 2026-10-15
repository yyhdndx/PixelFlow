"""Print the versioned Skill catalog and JSON schemas without writing files."""
import json
from pixelflow_intelligence.skills import create_registry


if __name__ == "__main__":
    print(json.dumps(create_registry().describe(), ensure_ascii=False, indent=2))
