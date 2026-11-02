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
it('paginates multiple media behind arrows instead of tiling them', async () => {
  request.mockClear()
  const wrapper = mount(PostMedia, { props: { clips: [
    { type: 0, url: 'https://example.com/a.jpg' },
    { type: 0, url: 'https://example.com/b.jpg' },
    { type: 0, url: 'https://example.com/c.jpg' }
  ] } })
  await flushPromises()
  expect(wrapper.findAll('img')).toHaveLength(1)
  expect(wrapper.find('.stage img').attributes('src')).toBe('https://example.com/a.jpg')
  expect(wrapper.text()).toContain('1 / 3')
  expect(wrapper.findAll('.arrow')).toHaveLength(2)
  expect(wrapper.findAll('.dot')).toHaveLength(3)
  await wrapper.find('.arrow.right').trigger('click')
  expect(wrapper.find('.stage img').attributes('src')).toBe('https://example.com/b.jpg')
  expect(wrapper.text()).toContain('2 / 3')
  await wrapper.find('.dot').trigger('click')
  expect(wrapper.find('.stage img').attributes('src')).toBe('https://example.com/a.jpg')
  await wrapper.find('.arrow.left').trigger('click')
  expect(wrapper.find('.stage img').attributes('src')).toBe('https://example.com/c.jpg')
  wrapper.unmount()
})
it('hides arrows for a single medium and resets to the first one on clips change', async () => {
  request.mockClear()
  const wrapper = mount(PostMedia, { props: { clips: [{ type: 0, url: 'https://example.com/only.jpg' }] } })
  await flushPromises()
  expect(wrapper.findAll('.arrow')).toHaveLength(0)
  expect(wrapper.findAll('.dot')).toHaveLength(0)
  await wrapper.setProps({ clips: [
    { type: 0, url: 'https://example.com/x.jpg' },
    { type: 0, url: 'https://example.com/y.jpg' }
  ] })
  await flushPromises()
  expect(wrapper.find('.stage img').attributes('src')).toBe('https://example.com/x.jpg')
  expect(wrapper.text()).toContain('1 / 2')
  wrapper.unmount()
})
