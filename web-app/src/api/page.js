import { reactive, onBeforeUnmount, watch } from 'vue'
import { request, session } from './client'

export function usePage(path, field = 'post_list', key = 'post_id') {
  let revision = 0
  const page = reactive({ items: [], loading: false, error: '', hasMore: false, cursor: null, load, reset })
  function reset() {
    revision++
    page.items = []; page.cursor = null; page.hasMore = false; page.error = ''; page.loading = false
  }
  async function load() {
    if (page.loading) return
    const current = revision
    const endpoint = path()
    if (!endpoint) return
    page.loading = true; page.error = ''
    try {
      const result = await request(endpoint + (page.cursor == null ? '' : `&cursor=${encodeURIComponent(page.cursor)}`))
      if (current !== revision) return
      const seen = new Set(page.items.map(item => item[key]))
      page.items.push(...(result[field] || []).filter(item => { if (seen.has(item[key])) return false; seen.add(item[key]); return true }))
      page.cursor = result.next_cursor
      page.hasMore = result.has_more === 1 && result.next_cursor != null
    } catch (error) { if (current === revision) page.error = error.message }
    finally { if (current === revision) page.loading = false }
  }
  watch(() => session.generation, reset)
  onBeforeUnmount(reset)
  return page
}
