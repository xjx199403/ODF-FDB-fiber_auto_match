from sentence_transformers import SentenceTransformer, util
from fastapi import FastAPI
from pydantic import BaseModel
import re
import os
import sys
import logging

# 设置日志
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


# 处理打包后的路径问题
def get_base_dir():
    if getattr(sys, 'frozen', False):
        # 打包后的exe文件所在目录
        return os.path.dirname(sys.executable)
    else:
        # 开发环境
        return os.path.dirname(os.path.abspath(__file__))


BASE_DIR = get_base_dir()


def find_model_path():
    possible_paths = [
        # 打包后的路径
        os.path.join(BASE_DIR, "models", "paraphrase-multilingual-MiniLM-L12-v2"),
        # 开发环境路径
        os.path.join(BASE_DIR, "..", "models", "paraphrase-multilingual-MiniLM-L12-v2"),
        # 直接相对路径
        "models/paraphrase-multilingual-MiniLM-L12-v2"
    ]

    for path in possible_paths:
        if os.path.exists(path):
            logger.info(f"找到模型路径: {path}")
            return path

    # 列出目录内容帮助调试
    logger.error(f"模型路径不存在。BASE_DIR: {BASE_DIR}")
    if os.path.exists(BASE_DIR):
        logger.info(f"BASE_DIR下的内容: {os.listdir(BASE_DIR)}")
        models_dir = os.path.join(BASE_DIR, "models")
        if os.path.exists(models_dir):
            logger.info(f"models目录下的内容: {os.listdir(models_dir)}")

    return None


model_path = find_model_path()

try:
    logger.info(f"正在加载模型从: {model_path}")
    model = SentenceTransformer(model_path)
    logger.info("模型加载成功")
except Exception as e:
    logger.error(f"模型加载失败: {e}")
    input("按Enter键退出...")
    sys.exit(1)

app = FastAPI()

# 这里放您原有的业务代码...
digit_penalty_weight = 0.7
missing_penalty = 0.9


def auto_direction(keyword: str) -> str:
    left_keywords = ["街", "路", "巷", "层", "楼", "号", "室", "单元", "社区", "个", "点", "期", "栋", "小区"]
    if any(k in keyword for k in left_keywords):
        return "left"
    else:
        return "right"


def clean_text(text: str) -> str:
    return re.sub(r"[^\u4e00-\u9fff\s]", "", text).strip()


def extract_with_pattern(text, keyword, direction="left"):
    if direction == "left":
        m = re.search(r"([0-9]+|[A-G])\s*{}".format(re.escape(keyword)), text)
        if m:
            return m.group(1)
    else:
        m = re.search(r"{}\s*([0-9]+|[A-G])".format(re.escape(keyword)), text)
        if m:
            return m.group(1)
    return None


def rule_similarity(s1, s2, keywords: dict):
    score = 1.0
    matched_keywords = []

    for kw, weight in keywords.items():
        direction = auto_direction(kw)
        v1 = extract_with_pattern(s1, kw, direction)
        v2 = extract_with_pattern(s2, kw, direction)

        if v1 and v2:
            matched_keywords.append(kw)
            if v1 == v2:
                score *= (0.75 + 0.3 * weight)
            else:
                score *= (digit_penalty_weight ** weight)
        elif v1 or v2:
            matched_keywords.append(kw)
            score *= (missing_penalty ** weight)
    return score, matched_keywords

def semantic_similarity(text1, text2):
    text1_clean = clean_text(text1)
    text2_clean = clean_text(text2)
    emb1 = model.encode(text1_clean, convert_to_tensor=True)
    emb2 = model.encode(text2_clean, convert_to_tensor=True)
    return util.cos_sim(emb1, emb2).item()

def combined_similarity(s1, s2, keywords: dict):
    emb1 = model.encode(s1, convert_to_tensor=True)
    emb2 = model.encode(s2, convert_to_tensor=True)
    semantic_sim = float(util.pytorch_cos_sim(emb1, emb2)[0])

    rule_sim, matched_keywords = rule_similarity(s1, s2, keywords)
    final_score = semantic_sim * rule_sim

    if 0.9 > final_score > 0.8:
        final_score = semantic_similarity(s1, s2)

    return {
        "semantic": round(semantic_sim, 4),
        "rule": round(rule_sim, 4),
        "fin": round(final_score, 4),
        "matched_keywords": ",".join(matched_keywords) if matched_keywords else ""
    }


class SimilarityRequest(BaseModel):
    sentence1: str
    sentence2: str
    keywords: dict


@app.post("/similarity")
def compute_similarity(data: SimilarityRequest):
    logger.info(f"收到请求: {data}")
    result = combined_similarity(data.sentence1, data.sentence2, data.keywords)
    logger.info(f"返回结果: {result}")
    return result

if __name__ == "__main__":
    import uvicorn

    logger.info("启动FastAPI服务...")
    uvicorn.run(app, host="0.0.0.0", port=8000, log_level="info")