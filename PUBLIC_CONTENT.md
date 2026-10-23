# Public content checks

Install the local commit and push guards after cloning:

```sh
git config core.hooksPath .githooks
```

Use the GitHub noreply email shown in your GitHub email settings for public commits. The checker blocks private commit emails, credentials, local account paths, internal drafts and generated packages in source Git. Push checks also inspect outgoing historical commits, including files deleted from the tip.

Run the check manually before publishing:

```sh
python tools/check_public_content.py --all-history
```

GitHub runs the same check for pushes and pull requests. Keep maintained runtime resources, tests and third-party license notices. Add an email to `.public-content.json` only when it is an intentionally public project contact; do not add personal addresses or local identifiers as exceptions.

Release binaries require a separate check of their embedded files: compression can hide machine paths or credentials from a source scan. For NaCL, use `npm.cmd run release` in `app/` so local compiler paths are remapped and checked before packaging.

These checks are safeguards, not a guarantee against every kind of personal information. Review screenshots, documents and new public assets before publishing. Do not bypass a failed hook by force-adding files or skipping verification.
