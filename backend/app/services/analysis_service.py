from __future__ import annotations

import os
from typing import List

from openai import OpenAI


def run_daily_insight(date: str, locale: str, context_hint: str) -> tuple[str, List[str], List[str]]:
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        return (
            "OpenAI API key is not configured. Returning deterministic fallback summary.",
            ["No AI anomaly detection (OPENAI_API_KEY missing)"],
            ["Configure OPENAI_API_KEY for richer daily insights."],
        )

    client = OpenAI(api_key=api_key)
    prompt = (
        "You are a diabetes analytics assistant for personal R&D. "
        f"Date: {date}, locale: {locale}. "
        "Return concise JSON with keys summary, anomalies, recommendations. "
        f"Context: {context_hint}"
    )

    response = client.responses.create(
        model="gpt-5-mini",
        input=prompt,
        max_output_tokens=500,
    )

    text = response.output_text or "No response"
    return (
        text,
        ["AI-generated insight available in summary"],
        ["Validate suggestions with deterministic safety policy before action."],
    )
