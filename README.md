# Expository

### Explore the story of your repository.

A mobile-first, dark-first visual explorer for GitHub repositories, with just enough rainbow to make a directory listing feel alive.

**[Open Expository](https://gamesbyian.github.io/Expository/)**

Expository browses **public repositories without login** and can browse selected **private repositories** using a fine-grained GitHub personal access token with **Contents: Read-only** access. Tokens stay in the browser tab's memory: they are not saved to local storage, embedded in links, or sent to an Expository server. Use only your own trusted deployment when supplying a token. A user with a private-repo link still needs their own GitHub access.

## What you can do

- Explore branches and collapsible nested folders
- Search paths across a repository and browse recursive image galleries
- Switch between gallery thumbnails and compact file lists
- Preview SVGs, photos, plain/safe Markdown, source files, PDFs, and supported media
- Pin two images for side-by-side comparison or a touch-friendly reveal slider
- Copy deep links to folders and previews
- Switch dark/light appearance, and clear private browsing credentials explicitly

Expository is **read-only**, uses direct requests to the GitHub API, and has no advertising, analytics, server-side session store, or dependency-loaded remote scripts. GitHub API rate limits and tree-index truncation may limit results on large repositories. It is not a replacement for GitHub's editor.

## How this site is published

This is a **public, application-only** deployment. Only the reviewed standalone `index.html` is copied from the private development source `gamesbyian/general-purpose/tools/expository/`. The private repository's other files, manifests, histories, workflows, and credentials are not published here.

The `.expository-managed` file records that this is the designated deployment target for guarded future app updates. `.nojekyll` disables Jekyll transformations.

For issues and implementation work, see the [private development source](https://github.com/gamesbyian/general-purpose/tree/main/tools/expository) if you have access.
