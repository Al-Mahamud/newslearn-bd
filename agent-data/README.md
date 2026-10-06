# agent-data

The hand-over point between the analysis agent and the database.

1. The **analysis agent** reads the newspapers and writes its results here, in a folder
   named with the date: `agent-data/2026-10-07/anything.json`. Give it three files:
   [`ANALYSIS.md`](ANALYSIS.md) (how to analyse the paper), [`FORMAT.md`](FORMAT.md)
   (the shape of the output) and [`example.json`](example.json) (a working example).
2. **You** run the sender from the project folder:

   ```bash
   ./send-news              # send today's folder
   ./send-news --dry-run    # only check the files; save nothing
   ./send-news --date 2026-10-06
   ./send-news --all        # every dated folder
   ```

The sender checks every file, saves the good ones to the database (they appear in the app
straight away), and lists any problems with the file and field named. It writes a
`SENT.txt` summary into the day's folder. Sending the same folder twice is safe: articles
are updated, not duplicated.

One-time setup: create `backend/.env` containing your Neon connection string:

```
DATABASE_URL=postgresql://...
```

The dated folders stay on this computer; they are not uploaded to GitHub.
