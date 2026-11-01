import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import PostMedia from '../src/components/PostMedia.vue'
import { request } from '../src/api/client'
vi.mock('../src/api/client', () => ({ request: vi.fn() }))
it('reads private media through the authenticated Java client and releases its URL', async () => {
  request.mockResolvedValue(new Blob(['image']))
  URL.createObjectURL = vi.fn(() => 'blob:test-image')
  URL.revokeObjectURL = vi.fn()
  const wrapper = mount(PostMedia, { props: { clips: [{ type: 0, url: 'http://127.0.0.1:8080/api/media/test/content' }] } })
  await flushPromises()
  expect(request).toHaveBeenCalledWith('/api/media/test/content', { blob: true })
  expect(wrapper.find('img').attributes('src')).toBe('blob:test-image')
  wrapper.unmount()
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:test-image')
})
it('renders existing numeric video types without sending external URLs to the API client', async () => {
  request.mockClear()
  const wrapper = mount(PostMedia, { props: { clips: [{ type: 1, url: 'https://example.com/video.mp4' }] } })
  await flushPromises()
  expect(wrapper.find('video').exists()).toBe(true)
  expect(request).not.toHaveBeenCalled()
  wrapper.unmount()
})
