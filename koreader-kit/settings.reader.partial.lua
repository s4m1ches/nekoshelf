-- Nekoshelf KOReader Kit: PARTIAL settings.reader.lua.
-- This file is NOT meant to replace koreader/settings.reader.lua.
-- Copy the keys you want into your own settings.reader.lua while KOReader is
-- closed (KOReader rewrites that file on exit and would overwrite your edit
-- otherwise). Only device, refresh, font, UI and reading keys are listed;
-- nothing here refers to books, history, accounts or servers.
return {
    -- UI / interaction
    ["activate_menu"] = "tap",
    ["back_in_filemanager"] = "default",
    ["back_in_reader"] = "previous_location",
    ["back_to_exit"] = "prompt",
    ["input_invert_page_turn_keys"] = true,
    ["page_turns_tap_zones"] = "default",
    ["scroll_method"] = "classic",
    ["inertial_scroll"] = false,
    ["language"] = "ru",
    ["keyboard_layouts"] = { [1] = "ru", [2] = "en" },
    ["keyboard_key_font_size"] = 22,
    ["flash_keyboard"] = true,
    ["flash_ui"] = false,
    ["ui_font_name"] = "Noto Sans",
    ["start_with"] = "bookshelf",          -- needs the Bookshelf plugin
    ["dimension_units"] = "mm",
    ["duration_format"] = "classic",
    ["collate"] = "strcoll",
    ["show_bottom_menu"] = false,
    ["toc_items_per_page"] = 14,
    ["bookmarks_items_per_page"] = 14,
    ["bookmarks_items_font_size"] = 19,
    ["bookmarks_items_text_type"] = "note",

    -- e-ink refresh (works together with patches/1-nekoshelf-eink-fullrefresh.lua)
    ["full_refresh_count"] = 5,
    ["night_full_refresh_count"] = 5,
    ["refresh_on_chapter_boundaries"] = true,
    ["refresh_on_pages_with_images"] = true,
    ["color_rendering"] = false,
    ["night_mode"] = false,
    ["low_pan_rate"] = false,

    -- power / screen
    ["auto_standby_timeout_seconds"] = -1,
    ["autodim_starttime_minutes"] = -1,
    ["autodim_duration_seconds"] = 5,
    ["autodim_fraction"] = 20,
    ["device_status_battery_interval_minutes"] = 10,
    ["device_status_battery_threshold"] = 20,
    ["device_status_battery_threshold_high"] = 100,
    ["screensaver_type"] = "disable",
    ["screensaver_delay"] = "disable",
    ["screensaver_show_message"] = true,
    ["screensaver_img_background"] = "black",
    ["screensaver_stretch_images"] = false,

    -- reading: typography
    ["cre_font"] = "Literata",
    ["copt_font_size"] = 21,
    ["copt_font_base_weight"] = 0.5,
    ["copt_font_hinting"] = 2,
    ["copt_font_kerning"] = 3,
    ["copt_line_spacing"] = 100,
    ["copt_word_spacing"] = { [1] = 95, [2] = 75 },
    ["copt_word_expansion"] = 5,
    ["copt_t_page_margin"] = 45,
    ["copt_b_page_margin"] = 45,
    ["copt_h_page_margins"] = { [1] = 15, [2] = 15 },
    ["hyphenation"] = true,
    ["text_lang_fallback"] = "ru-US",
    ["highlight_lighten_factor"] = 0.2,
    ["default_highlight_action"] = "dictionary",
    ["style_tweaks"] = {
        ["a_black"] = true,
        ["footnote-inpage_epub"] = true,
        ["footnote-inpage_fb2"] = true,
        ["inpage_footnote_font-size_smaller"] = true,
        ["widows_orphans_avoid"] = true,
    },

    -- reading: classic footer (the Bookends plugin replaces it, see bookends.lua)
    ["reader_footer_mode"] = 0,
    ["footer"] = {
        ["disabled"] = false,
        ["battery"] = true,
        ["time"] = true,
        ["percentage"] = true,
        ["book_chapter"] = true,
        ["chapter_progress"] = true,
        ["chapter_progress_bar"] = true,
        ["progress_bar_position"] = "above",
        ["progress_style_thin"] = true,
        ["progress_style_thin_height"] = 3,
        ["toc_markers"] = true,
        ["toc_markers_width"] = 2,
        ["text_font_size"] = 14,
        ["item_prefix"] = "icons",
        ["items_separator"] = "bar",
        ["reclaim_height"] = true,
        ["hide_empty_generators"] = true,
        ["all_at_once"] = true,
        ["align"] = "center",
    },

    -- reading statistics plugin (settings only, not data)
    ["statistics"] = {
        ["is_enabled"] = true,
        ["min_sec"] = 5,
        ["max_sec"] = 120,
        ["calendar_start_day_of_week"] = 2,
    },
}
