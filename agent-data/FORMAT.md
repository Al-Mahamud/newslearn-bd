# Data format for analysed news

Give this file to the analysis agent. It describes exactly what to produce so the news can
be sent to the NewsLearn BD database. A complete, valid example is in
[`example.json`](example.json). How to choose and analyse the news is in
[`ANALYSIS.md`](ANALYSIS.md).

## Where to save

Inside this `agent-data` folder, create a folder named with today's date and put the
file(s) in it:

```
agent-data/
  2026-10-07/            <- today's date, YYYY-MM-DD
    daily-star.json      <- any file name ending in .json
    prothom-alo.json     <- one file or several; all are sent
```

Use UTF-8. Bangla text must be real Bangla script, not escaped or transliterated.

## File shape

```json
{
  "model": "name of the AI model that wrote this",
  "articles": [ { ...one article... }, { ...another... } ]
}
```

## One article

| Field | Required | Rules |
|---|---|---|
| `source` | yes | Newspaper name, written the same way every time: `The Daily Star` or `Prothom Alo` |
| `title` | yes | The headline in English (translate a Bangla headline). 5–500 characters |
| `published_on` | yes | The newspaper's date, `YYYY-MM-DD` |
| `url` | no | Public web link to the article, or `null` |
| `page` | no | Print page, e.g. `"1"` or `"B3"`, or `null` |
| `image_url` | no | Public link to the article's picture, or `null` |
| `excerpt` | no | One or two sentences in your own words. **Never the article's full text** |
| `analysis` | yes | The study notes, described below |

## `analysis`

All nine fields are required. Use an empty list `[]` when there is nothing to put in a list.

| Field | Type | Rules |
|---|---|---|
| `category` | text | Exactly one of: `bangladesh`, `international`, `economy`, `science_technology`, `environment`, `sports`, `other`. ICT news is `science_technology` |
| `summary` | text | 2–4 sentences of normal newspaper English, in your own words. Must not be empty |
| `easy_summary` | text | The same content in simple English: short sentences, common words |
| `bangla_summary` | text | The same content in natural Bangla script. Must not be empty |
| `vocabulary` | list | 3–8 useful difficult words from the article (see below). At most 8 are kept |
| `facts` | list | Up to 8 things worth memorising (see below) |
| `exam_importance` | whole number | 0–100. How likely this is to matter in a government-job exam. 60 or more is shown as "Exam important" in the app |
| `exam_reason` | text | One sentence: why it is worth remembering |
| `questions` | list | 0–3 multiple-choice questions (see below) |

### One `vocabulary` item — all seven fields required

| Field | Rules |
|---|---|
| `word` | Dictionary form, lowercase: `oversee`, not `Oversees` |
| `part_of_speech` | `noun`, `verb`, `adjective`, `adverb`, `phrase` … |
| `meaning_en` | Meaning in plain English, as used in this article |
| `meaning_bn` | Meaning in Bangla script |
| `example_sentence` | A new, simple sentence using the word |
| `context_sentence` | The sentence from the article where the word appears |
| `difficulty` | Exactly one of: `easy`, `medium`, `hard` |

### One `facts` item — all three fields required

| Field | Rules |
|---|---|
| `kind` | Exactly one of: `number`, `organization`, `person`, `place`, `date`, `fact` |
| `text` | The fact itself: `8.5%`, `ICT Division`, `1 January 2027` |
| `detail` | One line of context: what the number measures, the person's role. May be `""` |

### One `questions` item — all four fields required

| Field | Rules |
|---|---|
| `question` | The question, answerable from the article alone |
| `options` | **Exactly four** answers, all different |
| `correct_index` | Position of the right answer, counting from **0** (so 0, 1, 2 or 3) |
| `explanation` | One sentence saying why that answer is right |

A question that does not have exactly four different options, or whose `correct_index` is
not 0–3, is dropped.

## Common mistakes

- `correct_index` counted from 1 instead of 0.
- A `category`, `kind` or `difficulty` value that is not one of the listed words.
- A missing field inside `vocabulary`, `facts` or `questions`. Every listed field must be there.
- `exam_importance` written as text (`"85"`) instead of a number (`85`).
- Facts or figures that are not in the article. The reader is tested on these notes.
- The article's full text pasted into `excerpt` or `summary`.

## Accepted shortcuts

A file may also be just a list of articles `[ {...}, {...} ]`, or a single article
`{...}`. The full shape above is preferred because it records the model name.
