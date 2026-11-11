let selection = null

export function prepareCreation(files, generation, from) {
  selection = { files: Array.from(files), generation, from }
}

export function takeCreation(generation) {
  const selected = selection
  selection = null
  return selected?.generation === generation ? selected : null
}

export function clearCreation() {
  selection = null
}
